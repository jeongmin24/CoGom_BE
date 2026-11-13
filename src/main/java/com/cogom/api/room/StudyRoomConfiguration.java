package com.cogom.api.room;

import com.cogom.api.AllowedOrigins;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class StudyRoomConfiguration implements WebSocketConfigurer {
    private final StudyRoomHandler room;
    private final AllowedOrigins origins;

    public StudyRoomConfiguration(StudyRoomHandler room, AllowedOrigins origins) {
        this.room = room;
        this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(room, "/ws/study-room");
        // 지정하지 않으면 같은 출처의 연결만 허용된다.
        if (!origins.isEmpty()) {
            registration.setAllowedOrigins(origins.toArray());
        }
    }
}
