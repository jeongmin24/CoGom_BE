package com.cogom.api.room;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StudyRoomHandler extends TextWebSocketHandler {
    private static final int CAPACITY = 6;
    private static final int FOCUS_SECONDS = 1500;
    private static final double WAKE_DISTANCE = 1.8;
    private static final long WAKE_COOLDOWN_MS = 5000;
    private static final long KNOCK_DURATION_MS = 1600;
    private static final long JUMP_DURATION_MS = 800;
    private static final long STUDY_STYLE_INTERVAL_MS = 20000;
    private static final List<String> STUDY_STYLES = List.of("writing", "headphones", "thinking", "reading", "typing");
    private static final List<List<Double>> SEATS = List.of(
            List.of(-3.0, 0.0), List.of(0.0, 0.0), List.of(3.0, 0.0),
            List.of(-3.0, 2.4), List.of(0.0, 2.4), List.of(3.0, 2.4));

    private final JsonMapper mapper;
    private final Map<String, WebSocketSession> sessions = new LinkedHashMap<>();
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<Todo> todos = new ArrayList<>();
    private final Map<WakePair, Long> wakeTimes = new LinkedHashMap<>();

    public StudyRoomHandler(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(4096);
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 5000, 65536));
    }

    @Override
    protected synchronized void handleTextMessage(WebSocketSession rawSession, TextMessage message) {
        WebSocketSession session = sessions.get(rawSession.getId());
        if (session == null) return;

        try {
            JsonNode request = mapper.readTree(message.getPayload());
            require(request != null && request.isObject(), "올바른 JSON 객체를 보내주세요.");
            String type = text(request, "type", 30);
            updateTimedStates();
            if ("join".equals(type)) {
                join(session, text(request, "name", 16));
                return;
            }

            Player player = players.get(session.getId());
            require(player != null, "먼저 스터디룸에 입장해 주세요.");
            switch (type) {
                case "move" -> move(player, request);
                case "jump" -> jump(player);
                case "timer_state" -> updateTimer(player, request);
                case "wake_up" -> wake(session, player, text(request, "targetId", 64));
                case "todo_complete" -> completeTodo(player, text(request, "text", 70));
                default -> throw new IllegalArgumentException("지원하지 않는 이벤트입니다.");
            }
        } catch (JacksonException exception) {
            send(session, new ErrorMessage("error", "JSON 형식을 확인해 주세요."));
        } catch (IllegalArgumentException exception) {
            send(session, new ErrorMessage("error", exception.getMessage()));
        }
    }

    private void join(WebSocketSession session, String name) {
        Player existing = players.get(session.getId());
        if (existing == null) {
            require(players.size() < CAPACITY, "지금은 자리가 모두 찼어요. 잠시 후 다시 입장해 주세요.");
            int seat = availableSeat();
            existing = new Player(UUID.randomUUID().toString(), name, seat);
            players.put(session.getId(), existing);
        }
        send(session, new Welcome("welcome", existing.id, playerViews(), List.copyOf(todos), System.currentTimeMillis()));
        broadcast();
    }

    private int availableSeat() {
        for (int seat = 0; seat < CAPACITY; seat++) {
            final int candidate = seat;
            if (players.values().stream().noneMatch(player -> player.seat == candidate)) return seat;
        }
        throw new IllegalArgumentException("지금은 자리가 모두 찼어요.");
    }

    private void move(Player player, JsonNode request) {
        require(!"studying".equals(player.status), "잠깐 쉬기를 누르면 자리에서 일어날 수 있어요.");
        require(player.knockUntil == null, "친구를 깨운 뒤 이동할 수 있어요.");
        JsonNode position = request.get("position");
        require(position != null && position.isArray() && position.size() == 2, "위치 좌표를 확인해 주세요.");
        double x = number(position.get(0), -5, 5, "방 안에서 이동해 주세요.");
        double z = number(position.get(1), -3, 3.5, "방 안에서 이동해 주세요.");
        double rotation = number(request.get("rotation"), -Math.PI * 2, Math.PI * 2, "회전 값을 확인해 주세요.");
        player.position = List.of(x, z);
        player.rotation = rotation;
        broadcast();
    }

    private void updateTimer(Player player, JsonNode request) {
        String status = text(request, "status", 16);
        require("studying".equals(status) || "resting".equals(status), "타이머 상태를 확인해 주세요.");
        double seconds = number(request.get("remainingSeconds"), 0, FOCUS_SECONDS, "집중 시간은 0~1500초로 설정해 주세요.");
        require(seconds == Math.floor(seconds), "집중 시간은 정수 초로 설정해 주세요.");
        require(!"studying".equals(status) || seconds > 0, "집중할 시간을 설정해 주세요.");
        long now = System.currentTimeMillis();
        player.status = status;
        player.remainingSeconds = (int) seconds;
        player.endsAt = "studying".equals(status) ? now + player.remainingSeconds * 1000L : null;
        if ("studying".equals(status)) {
            player.position = SEATS.get(player.seat);
            player.rotation = player.seat >= 3 ? Math.PI : 0;
            player.studyStyle = nextStudyStyle(player.studyStyle);
            player.styleChangedAt = now;
            player.knockUntil = null;
            player.jumpStartedAt = null;
        }
        broadcast();
    }

    private void jump(Player player) {
        require("resting".equals(player.status), "잠깐 쉬기를 누르면 점프할 수 있어요.");
        require(player.knockUntil == null, "친구를 깨운 뒤 점프할 수 있어요.");
        require(player.jumpStartedAt == null, "착지한 뒤 다시 점프할 수 있어요.");
        player.jumpStartedAt = System.currentTimeMillis();
        broadcast();
    }

    private void wake(WebSocketSession sender, Player player, String targetId) {
        require(!"studying".equals(player.status), "잠깐 쉬기를 누르고 친구에게 다가가 주세요.");
        require(player.jumpStartedAt == null, "착지한 뒤 친구를 깨워 주세요.");
        Map.Entry<String, Player> target = players.entrySet().stream()
                .filter(entry -> entry.getValue().id.equals(targetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("상대방이 방을 나갔어요."));
        Player other = target.getValue();
        require(!other.id.equals(player.id), "함께 있는 친구를 선택해 주세요.");
        require("resting".equals(other.status), "지금 집중하고 있는 친구예요.");
        double distance = Math.hypot(player.position.get(0) - other.position.get(0), player.position.get(1) - other.position.get(1));
        require(distance <= WAKE_DISTANCE, "친구에게 조금 더 가까이 다가가 주세요.");
        WakePair pair = new WakePair(player.id, other.id);
        long now = System.currentTimeMillis();
        require(now - wakeTimes.getOrDefault(pair, 0L) >= WAKE_COOLDOWN_MS, "친구가 알림을 확인할 시간을 주세요.");
        wakeTimes.put(pair, now);
        double dx = other.position.get(0) - player.position.get(0);
        double dz = other.position.get(1) - player.position.get(1);
        player.rotation = Math.atan2(dx, dz);
        player.knockUntil = now + KNOCK_DURATION_MS;
        broadcast();
        send(sessions.get(target.getKey()), new WakeUp("wake_up", player.id, player.name, other.id));
        send(sender, new WakeSent("wake_sent", other.id, other.name));
    }

    private void completeTodo(Player player, String text) {
        todos.add(new Todo(UUID.randomUUID().toString(), text, player.name, System.currentTimeMillis()));
        if (todos.size() > 12) todos.removeFirst();
        broadcast();
    }

    @Scheduled(fixedDelay = 500)
    public synchronized void updateTimedStates() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Player player : players.values()) {
            if (player.endsAt != null && player.endsAt <= now) {
                player.status = "resting";
                player.remainingSeconds = 0;
                player.endsAt = null;
                changed = true;
            } else if ("studying".equals(player.status) && now - player.styleChangedAt >= STUDY_STYLE_INTERVAL_MS) {
                player.studyStyle = nextStudyStyle(player.studyStyle);
                player.styleChangedAt = now;
                changed = true;
            }
            if (player.knockUntil != null && player.knockUntil <= now) {
                player.knockUntil = null;
                changed = true;
            }
            if (player.jumpStartedAt != null && player.jumpStartedAt + JUMP_DURATION_MS <= now) {
                player.jumpStartedAt = null;
                changed = true;
            }
        }
        if (changed) broadcast();
    }

    @Override
    public synchronized void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        Player removed = players.remove(session.getId());
        if (removed != null) {
            wakeTimes.keySet().removeIf(pair -> pair.fromId.equals(removed.id) || pair.targetId.equals(removed.id));
            broadcast();
        }
    }

    @Override
    public synchronized void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        session.close(CloseStatus.SERVER_ERROR);
    }

    private List<PlayerView> playerViews() {
        long now = System.currentTimeMillis();
        return players.values().stream().map(player -> player.view(now)).toList();
    }

    private void broadcast() {
        RoomState state = new RoomState("room_state", playerViews(), List.copyOf(todos), System.currentTimeMillis());
        for (String sessionId : List.copyOf(players.keySet())) {
            send(sessions.get(sessionId), state);
        }
    }

    private void send(WebSocketSession session, Object event) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
        } catch (IOException | IllegalStateException exception) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // The container's close callback releases the participant.
            }
        }
    }

    private static String text(JsonNode node, String field, int maximum) {
        JsonNode value = node.get(field);
        require(value != null && value.isTextual(), field + " 값을 확인해 주세요.");
        String result = value.asString().strip();
        require(!result.isEmpty() && result.length() <= maximum, field + " 길이를 확인해 주세요.");
        return result;
    }

    private static double number(JsonNode node, double minimum, double maximum, String message) {
        require(node != null && node.isNumber(), message);
        double value = node.doubleValue();
        require(Double.isFinite(value) && value >= minimum && value <= maximum, message);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String nextStudyStyle(String previous) {
        int offset = ThreadLocalRandom.current().nextInt(1, STUDY_STYLES.size());
        int index = (STUDY_STYLES.indexOf(previous) + offset) % STUDY_STYLES.size();
        return STUDY_STYLES.get(index);
    }

    private static final class Player {
        private final String id;
        private final String name;
        private final int seat;
        private List<Double> position;
        private double rotation;
        private String status = "resting";
        private int remainingSeconds = FOCUS_SECONDS;
        private Long endsAt;
        private String studyStyle = "typing";
        private long styleChangedAt = System.currentTimeMillis();
        private Long knockUntil;
        private Long jumpStartedAt;

        private Player(String id, String name, int seat) {
            this.id = id;
            this.name = name;
            this.seat = seat;
            this.position = SEATS.get(seat);
            this.rotation = seat >= 3 ? Math.PI : 0;
        }

        private PlayerView view(long now) {
            int remaining = endsAt == null ? remainingSeconds : (int) Math.max(0, Math.ceil((endsAt - now) / 1000.0));
            return new PlayerView(id, name, position, rotation, status, remaining, endsAt,
                    seat, studyStyle, styleChangedAt, knockUntil, jumpStartedAt);
        }
    }

    public record PlayerView(String id, String name, List<Double> position, double rotation,
            String status, int remainingSeconds, Long endsAt, int seatIndex,
            String studyStyle, long styleChangedAt, Long knockUntil, Long jumpStartedAt) {}
    public record Todo(String id, String text, String authorName, long completedAt) {}
    public record Welcome(String type, String selfId, List<PlayerView> players, List<Todo> todos, long serverNow) {}
    public record RoomState(String type, List<PlayerView> players, List<Todo> todos, long serverNow) {}
    public record WakeUp(String type, String fromId, String fromName, String targetId) {}
    public record WakeSent(String type, String targetId, String targetName) {}
    public record ErrorMessage(String type, String message) {}
    private record WakePair(String fromId, String targetId) {}
}
