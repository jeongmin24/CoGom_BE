package com.cogom.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebCorsConfiguration implements WebMvcConfigurer {
    private final AllowedOrigins origins;

    public WebCorsConfiguration(AllowedOrigins origins) {
        this.origins = origins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.isEmpty()) {
            return;
        }
        // 토큰은 Authorization 헤더로 전달하므로 쿠키(credentials)는 허용하지 않는다.
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept")
                .maxAge(3600);
    }
}
