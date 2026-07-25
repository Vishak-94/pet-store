package com.petstore.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the storefront's auth-identity contract: the stable customer userId comes from
 * {@code getDetails()} (what {@link CustomerServiceAuthProvider} stashes at login), and
 * falls back to the username only when it isn't a String. This is the single decode point
 * the controllers share, so its behaviour is worth a direct test.
 */
class AuthenticatedUserTest {

    private static Authentication auth(Object details) {
        var token = new UsernamePasswordAuthenticationToken("alice", "jwt", List.of());
        token.setDetails(details);
        return token;
    }

    @Test
    void prefersStableUserId_fromDetails() {
        assertThat(AuthenticatedUser.userId(auth("cust-42"))).isEqualTo("cust-42");
    }

    @Test
    void fallsBackToUsername_whenDetailsNotAString() {
        // no details set (null) → username
        assertThat(AuthenticatedUser.userId(auth(null))).isEqualTo("alice");
        // non-String details (e.g. a web-auth WebAuthenticationDetails) → username
        assertThat(AuthenticatedUser.userId(auth(new Object()))).isEqualTo("alice");
    }

    @Test
    void nullAuthentication_isNull() {
        assertThat(AuthenticatedUser.userId(null)).isNull();
    }
}
