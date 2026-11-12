package com.cogom.api.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
class AccessTokens {
    static final long EXPIRES_IN_SECONDS = 1_800;

    private static final String ISSUER = "cogom-api";
    private static final String AUDIENCE = "cogom-fe";
    private static final JwsHeader SIGNATURE_HEADER = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
    private static final int MINIMUM_KEY_BYTES = 32;
    private static final String INVALID_KEY_MESSAGE = "COGOM_JWT_SECRET에는 32바이트 이상의 키를 Base64로 인코딩해 설정해 주세요.";

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;

    AccessTokens(@Value("${cogom.auth.jwt-secret:}") String configuredSecret) {
        SecretKey key = new SecretKeySpec(secretBytes(configuredSecret), "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(ISSUER),
                new JwtAudienceValidator(AUDIENCE)));
    }

    String issue(UUID accountId) {
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(accountId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(EXPIRES_IN_SECONDS))
                .build();
        return encoder.encode(JwtEncoderParameters.from(SIGNATURE_HEADER, claims)).getTokenValue();
    }

    Optional<UUID> accountId(String rawJwt) {
        try {
            Jwt jwt = decoder.decode(rawJwt);
            String subject = jwt.getSubject();
            if (subject == null || !hasValidLifetime(jwt)) {
                return Optional.empty();
            }
            UUID accountId = UUID.fromString(subject);
            return accountId.toString().equalsIgnoreCase(subject) ? Optional.of(accountId) : Optional.empty();
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean hasValidLifetime(Jwt jwt) {
        Instant issuedAt = jwt.getIssuedAt();
        Instant expiresAt = jwt.getExpiresAt();
        Instant now = Instant.now();
        return issuedAt != null && expiresAt != null && !issuedAt.isAfter(now) && expiresAt.isAfter(now)
                && Duration.between(issuedAt, expiresAt).equals(Duration.ofSeconds(EXPIRES_IN_SECONDS));
    }

    private static byte[] secretBytes(String configuredSecret) {
        if (configuredSecret.isEmpty()) {
            byte[] bytes = new byte[MINIMUM_KEY_BYTES];
            new SecureRandom().nextBytes(bytes);
            return bytes;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configuredSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        if (bytes.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        return bytes;
    }
}
