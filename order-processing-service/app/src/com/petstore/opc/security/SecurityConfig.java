package com.petstore.opc.security;

import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Verify-only security. The admin facade (/api/orders/**) is ADMIN-only; the admin
 * console (admin-office-service) forwards the acting admin's JWT, which this
 * service verifies with the bundled public key (auth-client). OPC holds no
 * credentials and issues no tokens.
 */
@Configuration
public class SecurityConfig {

    /** Role (without Spring's {@code ROLE_} prefix) required for the whole admin facade. */
    private static final String ADMIN_ROLE = "ADMIN";
    /** Customer role — a signed-in shopper (checkout intake), distinct from the admin console. */
    private static final String USER_ROLE = "USER";
    /** Public paths: health/metrics probes and the error dispatch. */
    private static final String[] PUBLIC_MATCHERS = {"/actuator/**", "/error"};
    /**
     * The checkout intake endpoint — the ONE facade path open to a customer (the storefront
     * proxies the shopper's JWT). Matched BEFORE {@link #ADMIN_MATCHERS} so this specific path
     * gets the customer-or-admin rule rather than falling into the admin-only {@code /api/orders/**}.
     */
    private static final String ORDER_INTAKE_MATCHER = "/api/orders/intake";
    /** The admin facade surface — the order workflow API + sales aggregation. */
    private static final String[] ADMIN_MATCHERS = {"/api/orders/**", "/api/sales/**", "/api/sales"};

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier, Environment env) throws Exception {
        // The H2 console (arbitrary SQL over the authoritative order store) is opened ONLY under
        // the 'dev' profile — matching application-dev.yml which is the only place the console is
        // enabled. In every other profile /h2-console/** is authenticated like any other path,
        // and the frame-options relaxation it needs is not applied.
        boolean devConsole = env.acceptsProfiles(Profiles.of("dev"));
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(PUBLIC_MATCHERS).permitAll();
                if (devConsole) {
                    auth.requestMatchers(new AntPathRequestMatcher("/h2-console/**")).permitAll();
                }
                // Checkout intake first (more specific): a signed-in shopper OR an admin. Must precede
                // the ADMIN_MATCHERS rule below, which would otherwise lock /api/orders/** to ADMIN only.
                auth.requestMatchers(ORDER_INTAKE_MATCHER).hasAnyRole(USER_ROLE, ADMIN_ROLE)
                    .requestMatchers(ADMIN_MATCHERS).hasRole(ADMIN_ROLE)
                    .anyRequest().authenticated();
            })
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                    res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) ->
                    res.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN)))
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        if (devConsole) {
            http.headers(h -> h.frameOptions(f -> f.sameOrigin()));   // H2 console renders in frames
        }
        return http.build();
    }
}
