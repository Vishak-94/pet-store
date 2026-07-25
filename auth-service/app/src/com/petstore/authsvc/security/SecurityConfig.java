package com.petstore.authsvc.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * auth-service is a headless JSON API: /auth/login is public (it IS the login),
 * actuator is public; everything else denied. Stateless — no sessions.
 *
 * <p>/auth/accounts stays public so anonymous customer registration can provision a
 * {@code USER} credential, but the {@link AuthJwtFilter} is wired in so a Bearer token
 * populates the SecurityContext — {@code AccountController} then requires ROLE_ADMIN to
 * provision any privileged (non-USER) role, closing the unauthenticated-ADMIN escalation.
 */
@Configuration
public class SecurityConfig {

    /** Actuator health/metrics — public so probes work without a token. */
    private static final String ACTUATOR_MATCHER = "/actuator/**";

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier, Environment env) throws Exception {
        // The H2 console (arbitrary SQL over the credential/account table) is opened ONLY under
        // the 'dev' profile — matching application-dev.yml which is the only place the console is
        // enabled. In every other profile /h2-console/** falls through to denyAll like anything
        // else, and the frame-options relaxation it needs is not applied.
        boolean devConsole = env.acceptsProfiles(Profiles.of("dev"));
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(AuthClient.LOGIN, AuthClient.ACCOUNTS, ACTUATOR_MATCHER).permitAll();
                if (devConsole) {
                    auth.requestMatchers(new AntPathRequestMatcher("/h2-console/**")).permitAll();
                }
                auth.anyRequest().denyAll();
            })
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        if (devConsole) {
            http.headers(h -> h.frameOptions(f -> f.sameOrigin()));
        }
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
