package com.petstore.authsvc.web;

import com.petstore.authsvc.domain.AccountEntity;
import com.petstore.authsvc.domain.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Account provisioning — how other services create a credential in the central
 * store without ever holding a password themselves. customer-service calls this
 * during customer registration (role USER); staff onboarding would call it with
 * SUPPLIER/ADMIN. Returns the stable userId the caller stores as a reference.
 *
 * <p>Kept minimal: this is the only write path into the account store besides seeds.
 */
@RestController
public class AccountController {

    /** Legacy UserEJB limits (MAX_USERID_LENGTH / MAX_PASSWD_LENGTH) preserved on the write path. */
    private static final int MAX_USERID_LENGTH = 25;
    private static final int MAX_PASSWD_LENGTH = 25;

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    public AccountController(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    public record ProvisionRequest(String userName, String password, String role) {
    }

    @PostMapping("/auth/accounts")
    public ResponseEntity<Map<String, String>> provision(@RequestBody ProvisionRequest req) {
        if (req.userName() == null || req.userName().isBlank()
                || req.password() == null || req.password().length() < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
        }
        if (req.userName().length() > MAX_USERID_LENGTH
                || req.password().length() > MAX_PASSWD_LENGTH
                || req.userName().indexOf('%') != -1 || req.userName().indexOf('*') != -1) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
        }
        if (accounts.existsById(req.userName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "duplicate_account"));
        }
        String role = (req.role() == null || req.role().isBlank()) ? "USER" : req.role();
        String userId = UUID.randomUUID().toString();
        accounts.save(new AccountEntity(req.userName(), encoder.encode(req.password()), userId, role));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("userId", userId, "role", role, "status", "provisioned"));
    }
}
