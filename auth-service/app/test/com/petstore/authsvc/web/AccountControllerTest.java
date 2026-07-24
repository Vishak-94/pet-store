package com.petstore.authsvc.web;

import com.petstore.authsvc.domain.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the account-provisioning input rules carried over from the legacy UserEJB.ejbCreate:
 * userName/password length caps (25) and the ban on '%'/'*' in userName. Each violation must
 * fail creation with 400 (invalid_request) and never reach the store.
 */
class AccountControllerTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AccountController controller = new AccountController(accounts, encoder);

    private ResponseEntity<Map<String, String>> provision(String user, String pass) {
        return controller.provision(new AccountController.ProvisionRequest(user, pass, "USER"));
    }

    @Test
    void rejects_userNameLongerThan25() {
        ResponseEntity<Map<String, String>> res = provision("a".repeat(26), "secret");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("error", "invalid_request");
    }

    @Test
    void rejects_passwordLongerThan25() {
        ResponseEntity<Map<String, String>> res = provision("bob", "p".repeat(26));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("error", "invalid_request");
    }

    @Test
    void rejects_userNameWithPercent() {
        ResponseEntity<Map<String, String>> res = provision("bo%b", "secret");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("error", "invalid_request");
    }

    @Test
    void rejects_userNameWithStar() {
        ResponseEntity<Map<String, String>> res = provision("bo*b", "secret");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("error", "invalid_request");
    }

    @Test
    void accepts_validRequestAtBoundary() {
        when(accounts.existsById("a".repeat(25))).thenReturn(false);
        when(encoder.encode("p".repeat(25))).thenReturn("hashed");
        ResponseEntity<Map<String, String>> res = provision("a".repeat(25), "p".repeat(25));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody()).containsKey("userId");
    }
}
