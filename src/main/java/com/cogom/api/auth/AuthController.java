package com.cogom.api.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.cogom.api.auth.AccountService.Account;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String EMAIL_ATOM = "[A-Z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final String DOMAIN_LABEL = "[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            EMAIL_ATOM + "(?:\\." + EMAIL_ATOM + ")*@" + DOMAIN_LABEL + "(?:\\." + DOMAIN_LABEL + ")+",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SIGNUP_FIELDS = Set.of("email", "password", "nickname");
    private static final Set<String> LOGIN_FIELDS = Set.of("email", "password");
    private final AccountService accounts;
    private final AccessTokens tokens;
    private final LoginAttempts loginAttempts = new LoginAttempts();

    public AuthController(AccountService accounts, AccessTokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Account> signup(@RequestBody JsonNode body) {
        checkFields(body, SIGNUP_FIELDS);
        String email = email(body);
        String password = password(body, 15);
        String nickname = text(body, "nickname").strip();
        if (nickname.isEmpty() || nickname.length() > 16 || !isValidUnicode(nickname)) {
            throw validationError("닉네임은 1~16자로 입력해 주세요.");
        }

        Account account = accounts.register(email, password, nickname)
                .orElseThrow(() -> new AuthException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS",
                        "이미 가입된 이메일입니다."));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(account);
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@RequestBody JsonNode body, HttpServletRequest request) {
        checkFields(body, LOGIN_FIELDS);
        String email = email(body);
        String password = password(body, 1);
        long retryAfter = loginAttempts.retryAfterSeconds(request.getRemoteAddr());
        if (retryAfter > 0) {
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.", retryAfter);
        }

        Account account = accounts.authenticate(email, password)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                        "이메일 또는 비밀번호를 확인해 주세요."));
        LoginResponse result = new LoginResponse(tokens.issue(account.id()), "Bearer",
                AccessTokens.EXPIRES_IN_SECONDS, account);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/me")
    public ResponseEntity<Account> currentAccount(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            throw invalidToken();
        }
        String token = authorization.substring(BEARER_PREFIX.length()).strip();
        Account account = tokens.accountId(token).flatMap(accounts::findById).orElseThrow(AuthController::invalidToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(account);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody() {
        return authError(validationError("올바른 JSON 객체를 보내 주세요."));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ErrorResponse> authError(AuthException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status).cacheControl(CacheControl.noStore());
        if ("INVALID_TOKEN".equals(exception.code)) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        if (exception.retryAfter > 0) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfter));
        }
        return response.body(new ErrorResponse(exception.code, exception.getMessage()));
    }

    private static void checkFields(JsonNode body, Set<String> allowedFields) {
        if (body == null || !body.isObject()) {
            throw validationError("올바른 JSON 객체를 보내 주세요.");
        }
        if (!allowedFields.containsAll(body.propertyNames())) {
            throw validationError("지원하지 않는 입력 필드가 있습니다.");
        }
    }

    private static String email(JsonNode body) {
        String email = text(body, "email").strip();
        if (email.length() > 254 || email.indexOf('@') > 64 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw validationError("올바른 이메일 주소를 입력해 주세요.");
        }
        return email.toLowerCase(Locale.ROOT);
    }

    private static String password(JsonNode body, int minimum) {
        String password = text(body, "password");
        int length = password.codePointCount(0, password.length());
        if (length < minimum || length > 128 || !isValidUnicode(password)) {
            throw validationError("비밀번호는 " + minimum + "~128자로 입력해 주세요.");
        }
        return password;
    }

    private static boolean isValidUnicode(String text) {
        return StandardCharsets.UTF_8.newEncoder().canEncode(text);
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.path(field);
        if (!value.isTextual()) {
            throw validationError(field + " 항목은 문자열로 입력해 주세요.");
        }
        return value.asString();
    }

    private static AuthException validationError(String message) {
        return new AuthException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static AuthException invalidToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "유효한 로그인 토큰을 보내 주세요.");
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, Account user) {}

    public record ErrorResponse(String code, String message) {}

    private static class AuthException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        private final long retryAfter;

        private AuthException(HttpStatus status, String code, String message) {
            this(status, code, message, 0);
        }

        private AuthException(HttpStatus status, String code, String message, long retryAfter) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }
}
