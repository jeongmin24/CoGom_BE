package com.cogom.api.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    private final PasswordEncoder legacyPasswordEncoder = new Pbkdf2PasswordEncoder(
            "", 16, 600_000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    private final String missingAccountHash = passwordEncoder.encode(UUID.randomUUID().toString());

    public AccountService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Account> register(String email, String password, String nickname) {
        Account account = new Account(UUID.randomUUID(), email, nickname);
        String passwordHash = passwordEncoder.encode(password);
        try {
            jdbc.sql("""
                    INSERT INTO cogom_account (id, email, nickname, password_hash)
                    VALUES (:id, :email, :nickname, :passwordHash)
                    """)
                    .param("id", account.id())
                    .param("email", account.email())
                    .param("nickname", account.nickname())
                    .param("passwordHash", passwordHash)
                    .update();
        } catch (DuplicateKeyException exception) {
            return Optional.empty();
        }
        return Optional.of(account);
    }

    Optional<Account> authenticate(String email, String password) {
        Optional<Credentials> credentials = jdbc.sql("""
                SELECT id, email, nickname, password_hash FROM cogom_account WHERE email = :email
                """)
                .param("email", email)
                .query((row, rowNum) -> new Credentials(readAccount(row), row.getString("password_hash")))
                .optional();

        if (credentials.isEmpty()) {
            // 등록 여부에 따른 비밀번호 검증 시간 차이 완화
            passwordEncoder.matches(password, missingAccountHash);
            return Optional.empty();
        }
        Credentials stored = credentials.get();
        boolean legacyHash = !stored.passwordHash().startsWith("$argon2id$");
        PasswordEncoder encoder = legacyHash ? legacyPasswordEncoder : passwordEncoder;
        if (!encoder.matches(password, stored.passwordHash())) {
            return Optional.empty();
        }
        if (legacyHash) {
            upgradePasswordHash(stored, password);
        }
        return Optional.of(stored.account());
    }

    Optional<Account> findById(UUID id) {
        return jdbc.sql("SELECT id, email, nickname FROM cogom_account WHERE id = :id")
                .param("id", id)
                .query((row, rowNum) -> readAccount(row))
                .optional();
    }

    private void upgradePasswordHash(Credentials stored, String password) {
        jdbc.sql("""
                UPDATE cogom_account SET password_hash = :newHash WHERE id = :id AND password_hash = :oldHash
                """)
                .param("newHash", passwordEncoder.encode(password))
                .param("id", stored.account().id())
                .param("oldHash", stored.passwordHash())
                .update();
    }

    private static Account readAccount(ResultSet row) throws SQLException {
        return new Account(row.getObject("id", UUID.class), row.getString("email"), row.getString("nickname"));
    }

    public record Account(UUID id, String email, String nickname) {}

    private record Credentials(Account account, String passwordHash) {}
}
