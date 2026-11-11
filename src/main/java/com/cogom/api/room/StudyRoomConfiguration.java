package com.cogom.api.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class StudyRoomConfiguration implements WebSocketConfigurer {
    private final StudyRoomHandler room;

    public StudyRoomConfiguration(StudyRoomHandler room) {
        this.room = room;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(room, "/ws/study-room")
                .setAllowedOrigins("http://127.0.0.1:5173", "http://localhost:5173");
    }
}
