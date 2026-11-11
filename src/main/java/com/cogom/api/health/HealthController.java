package com.cogom.api.health;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping("/api/v1/health")
    public HealthResponse getHealth() {
        return new HealthResponse("UP", Instant.now());
    }

    public record HealthResponse(String status, Instant serverNow) {}
}
