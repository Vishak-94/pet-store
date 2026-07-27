package com.petstore.authsvc.web;

import com.petstore.auth.client.AuthClient;
import com.petstore.authsvc.domain.AccountEntity;
import com.petstore.authsvc.security.JwtIssuer;
import com.petstore.authsvc.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The single authentication endpoint for the whole platform. Validates
 * credentials against the one account store and mints an RS256 token. Every user
 * — customer or staff — logs in here; every other service only verifies.
 */
@RestController
public class AuthController {

    /** Response field for the auth scheme; pairs with the token so clients send {@code Authorization: Bearer <token>}. */
    private static final String FIELD_TOKEN_TYPE = "tokenType";
    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String FIELD_ERROR = "error";
    private static final String ERROR_INVALID_CREDENTIALS = "invalid_credentials";

    private final AuthService auth;
    private final JwtIssuer jwt;

    public AuthController(AuthService auth, JwtIssuer jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    public record LoginRequest(String userName, String password) {
    }

    /**
     * Authenticate a username/password (BCrypt check in {@link AuthService}) and, on success,
     * mint an RS256 token carrying the account's userId + roles. The two failure modes (unknown
     * user, bad password) are deliberately indistinguishable to avoid user enumeration.
     *
     * <p>Example request:
     * <pre>{@code
     * POST /auth/login
     * Content-Type: application/json
     *
     * {"userName": "j2ee", "password": "j2ee"}
     * }</pre>
     *
     * <p>Example response (200):
     * <pre>{@code
     * {
     *   "token": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJqMmVlIiwidWlkIjoi...}.<sig>",
     *   "tokenType": "Bearer",
     *   "userId": "j2ee-0001",
     *   "roles": ["USER"]
     * }
     * }</pre>
     *
     * <p>Bad credentials → {@code 401 Unauthorized}:
     * <pre>{@code
     * {"error": "invalid_credentials"}
     * }</pre>
     */
    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        AccountEntity account = auth.authenticate(req.userName(), req.password()).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(FIELD_ERROR, ERROR_INVALID_CREDENTIALS));
        }
        List<String> roles = List.of(account.getRole());
        String token = jwt.issue(account.getUserName(), account.getUserId(), roles);
        return ResponseEntity.ok(Map.of(
                AuthClient.FIELD_TOKEN, token, FIELD_TOKEN_TYPE, TOKEN_TYPE_BEARER,
                AuthClient.FIELD_USER_ID, account.getUserId(), AuthClient.FIELD_ROLES, roles));
    }
}
