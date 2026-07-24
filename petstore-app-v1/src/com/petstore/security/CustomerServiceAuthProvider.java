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

    private final AuthClient auth;

    public CustomerServiceAuthProvider(AuthClient auth) {
        this.auth = auth;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String username = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        AuthClient.LoginResult result = auth.login(username, password)
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        var authorities = result.roles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
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
