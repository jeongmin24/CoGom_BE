package com.cogom.api;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 다른 출처에서 REST API와 WebSocket에 접근하도록 허용할 프런트엔드 출처 목록.
 * COGOM_ALLOWED_ORIGINS에 쉼표로 구분해 지정한다. 비어 있으면 같은 출처만 허용한다.
 */
@Component
public class AllowedOrigins {
    private final List<String> origins;

    public AllowedOrigins(@Value("${cogom.web.allowed-origins:}") String configured) {
        origins = Arrays.stream(configured.split(","))
                .map(String::strip)
                // 브라우저의 Origin 헤더에는 끝 슬래시가 없으므로 맞춰 둔다.
                .map(origin -> origin.replaceAll("/+$", ""))
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    public boolean isEmpty() {
        return origins.isEmpty();
    }

    public String[] toArray() {
        return origins.toArray(String[]::new);
    }
}
