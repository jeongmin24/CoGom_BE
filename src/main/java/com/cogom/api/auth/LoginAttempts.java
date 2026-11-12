package com.cogom.api.auth;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class LoginAttempts {
    private static final int ATTEMPTS_PER_WINDOW = 10;
    private static final int MAX_CLIENTS = 10_000;
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final Map<String, Window> clients = new LinkedHashMap<>();

    synchronized long retryAfterSeconds(String clientAddress) {
        long now = System.nanoTime();
        removeExpiredWindows(now);
        Window window = clients.get(clientAddress);
        if (window == null) {
            if (clients.size() >= MAX_CLIENTS) {
                long oldestStart = clients.values().iterator().next().startedAt();
                return secondsRemaining(now - oldestStart);
            }
            clients.put(clientAddress, new Window(now, 1));
            return 0;
        }
        if (window.attempts() >= ATTEMPTS_PER_WINDOW) {
            return secondsRemaining(now - window.startedAt());
        }
        clients.put(clientAddress, new Window(window.startedAt(), window.attempts() + 1));
        return 0;
    }

    private void removeExpiredWindows(long now) {
        Iterator<Window> windows = clients.values().iterator();
        while (windows.hasNext() && now - windows.next().startedAt() >= WINDOW_NANOS) {
            windows.remove();
        }
    }

    private static long secondsRemaining(long elapsedNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(WINDOW_NANOS - elapsedNanos - 1) + 1;
    }

    private record Window(long startedAt, int attempts) {}
}
