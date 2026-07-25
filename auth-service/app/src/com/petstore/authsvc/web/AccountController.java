package com.petstore.authsvc.web;

import com.petstore.auth.client.AuthClient;
import com.petstore.authsvc.domain.AccountEntity;
import com.petstore.authsvc.domain.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
    /** The only role the public (unauthenticated) registration path may provision. */
    private static final String PUBLIC_ROLE = "USER";
    /** Characters barred from a userName — legacy wildcard chars that would break lookups. */
    private static final char USERNAME_WILDCARD_PERCENT = '%';
    private static final char USERNAME_WILDCARD_STAR = '*';
    /** Role authority that gates provisioning of privileged (non-USER) accounts. */
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /** Response/error field names + status codes for the provision contract. */
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_DETAIL = "detail";
    private static final String FIELD_STATUS = "status";
    private static final String ERROR_INVALID_REQUEST = "invalid_request";
    private static final String ERROR_DUPLICATE_ACCOUNT = "duplicate_account";
    private static final String ERROR_ADMIN_REQUIRED = "admin_required";
    private static final String DETAIL_ADMIN_REQUIRED = "Provisioning a non-USER role requires an ADMIN token";
    private static final String STATUS_PROVISIONED = "provisioned";

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;

    public AccountController(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    public record ProvisionRequest(String userName, String password, String role) {
    }

    /**
     * Provision a new credential and return the stable userId the caller references. Runs the
     * legacy UserEJB validation guards (blank/length caps, barred wildcard chars), rejects
     * duplicates with 409, and enforces the privilege guard: an unauthenticated caller may mint
     * only a USER account — provisioning SUPPLIER/ADMIN requires a verified ADMIN token.
     * Returns 201 {@code {userId, role, status:"provisioned"}}; 400/409/403 on the guard failures.
     */
    @PostMapping("/auth/accounts")
    public ResponseEntity<Map<String, String>> provision(@RequestBody ProvisionRequest req) {
        if (req.userName() == null || req.userName().isBlank()
                || req.password() == null || req.password().length() < 1) {
            return ResponseEntity.badRequest().body(Map.of(FIELD_ERROR, ERROR_INVALID_REQUEST));
        }
        if (req.userName().length() > MAX_USERID_LENGTH
                || req.password().length() > MAX_PASSWD_LENGTH
                || req.userName().indexOf(USERNAME_WILDCARD_PERCENT) != -1
                || req.userName().indexOf(USERNAME_WILDCARD_STAR) != -1) {
            return ResponseEntity.badRequest().body(Map.of(FIELD_ERROR, ERROR_INVALID_REQUEST));
        }
        if (accounts.existsById(req.userName())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(FIELD_ERROR, ERROR_DUPLICATE_ACCOUNT));
        }
        // Privilege guard: the public registration path may only ever mint a USER credential.
        // Provisioning any privileged role (SUPPLIER/ADMIN) requires an authenticated ADMIN
        // caller — otherwise an anonymous request could self-issue an ADMIN account and then
        // log in with a fleet-wide admin token (unauthenticated privilege escalation).
        String requested = (req.role() == null || req.role().isBlank()) ? PUBLIC_ROLE : req.role().trim();
        String role = requested.toUpperCase();
        if (!PUBLIC_ROLE.equals(role) && !callerIsAdmin()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(FIELD_ERROR, ERROR_ADMIN_REQUIRED,
                            FIELD_DETAIL, DETAIL_ADMIN_REQUIRED));
        }
        String userId = UUID.randomUUID().toString();
        accounts.save(new AccountEntity(req.userName(), encoder.encode(req.password()), userId, role));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(AuthClient.FIELD_USER_ID, userId, AuthClient.FIELD_ROLE, role,
                        FIELD_STATUS, STATUS_PROVISIONED));
    }

    /** True if the current request carries a verified token with ROLE_ADMIN (via AuthJwtFilter). */
    private static boolean callerIsAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getAuthorities().stream()
                        .anyMatch(a -> ADMIN_AUTHORITY.equals(a.getAuthority()));
    }
}
