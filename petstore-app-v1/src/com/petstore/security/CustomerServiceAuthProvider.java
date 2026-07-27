package com.petstore.security;

import com.petstore.auth.client.AuthClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Authenticates the monolith's form-login by DELEGATING to auth-service (the
 * central IdP) via {@link AuthClient}. The monolith holds NO credentials.
 *
 * <p>On success the returned RS256 JWT is kept as the authentication credential,
 * so later calls to customer-service can forward it as a Bearer token (customer-
 * service verifies auth-service tokens with the same public key). Roles come from
 * the token.
 */
@Component
public class CustomerServiceAuthProvider implements AuthenticationProvider {

    /** Spring Security's authority prefix — a role "ADMIN" becomes authority "ROLE_ADMIN". */
    private static final String ROLE_PREFIX = "ROLE_";
    /** Message on a rejected login (kept generic — never reveal which of user/password was wrong). */
    private static final String BAD_CREDENTIALS_MSG = "Invalid username or password";

    private final AuthClient auth;

    public CustomerServiceAuthProvider(AuthClient auth) {
        this.auth = auth;
    }

    /**
     * Verifies username/password by delegating to auth-service. On success returns a
     * {@link UsernamePasswordAuthenticationToken} whose credential is the RS256 JWT and whose
     * details is the stable customer userId; authorities are the token roles mapped to
     * {@code ROLE_*}. Throws {@link BadCredentialsException} (generic message) when auth-service
     * rejects the login.
     */
    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        AuthClient.LoginResult result = auth.login(username, password)
                .orElseThrow(() -> new BadCredentialsException(BAD_CREDENTIALS_MSG));

        var authorities = result.roles().stream()
                .map(r -> new SimpleGrantedAuthority(ROLE_PREFIX + r)).toList();
        // Credentials = the JWT, so downstream calls can forward it as a Bearer token.
        var auth = new UsernamePasswordAuthenticationToken(username, result.token(), authorities);
        auth.setDetails(result.userId());   // stable userId for customer-service lookups
        return auth;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
