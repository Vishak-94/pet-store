package com.petstore.authsvc.web;

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

    private final AuthService auth;
    private final JwtIssuer jwt;

    public AuthController(AuthService auth, JwtIssuer jwt) {
        this.auth = auth;
        this.jwt = jwt;
    }

    public record LoginRequest(String userName, String password) {
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        AccountEntity account = auth.authenticate(req.userName(), req.password()).orElse(null);
        if (account == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "invalid_credentials"));
        }
        List<String> roles = List.of(account.getRole());
        String token = jwt.issue(account.getUserName(), account.getUserId(), roles);
        return ResponseEntity.ok(Map.of(
                "token", token, "tokenType", "Bearer",
                "userId", account.getUserId(), "roles", roles));
    }
}
