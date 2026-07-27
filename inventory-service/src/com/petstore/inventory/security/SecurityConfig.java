package com.petstore.inventory.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
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
 * Verify-only security for the supplier service. Tokens are minted by auth-service
 * and verified here with the bundled PUBLIC key via the auth-client
 * {@link AuthJwtFilter} — inventory-service holds NO credentials and cannot issue
 * tokens. Inventory view + restock → SUPPLIER (ADMIN also allowed).
 */
@Configuration
public class SecurityConfig {

    /** Default auth-service base URL when {@code services.auth.base-url} is not set. */
    private static final String DEFAULT_AUTH_BASE_URL = "http://localhost:8086";

    /**
     * Public paths: health/metrics probes, the login/logout endpoints, and the per-item
     * availability read. Availability is public because it feeds the storefront's stock badge on
     * public product pages (read-time composition) — the same "display data needs no auth" stance
     * catalog-service takes. NOTE the {@code GET}-only + single-item shape: the bulk
     * {@code /api/inventory} snapshot and all writes stay SUPPLIER/ADMIN via {@link #INVENTORY_MATCHERS}.
     */
    private static final String[] PUBLIC_MATCHERS = {"/actuator/**", "/inventory/login", "/inventory/logout"};
    /** Single-item availability read — public (see {@link #PUBLIC_MATCHERS}); matched ahead of the API rule. */
    private static final String AVAILABILITY_MATCHER = "/api/inventory/*/availability";
    /** Protected surface: the receiver UI + inventory JSON API. */
    private static final String[] INVENTORY_MATCHERS = {"/inventory/**", "/api/inventory/**"};
    /** Roles (without Spring's {@code ROLE_} prefix) allowed on the inventory surface. */
    private static final String ROLE_SUPPLIER = "SUPPLIER";
    private static final String ROLE_ADMIN = "ADMIN";

    /** JSON API prefix — requests under it get a JSON error, others a redirect to login. */
    private static final String API_PREFIX = "/api/";
    private static final String REDIRECT_LOGIN = "/inventory/login";
    private static final String REDIRECT_FORBIDDEN = "/inventory/login?forbidden";
    /** Error codes echoed in the JSON body for API auth failures. */
    private static final String ERROR_UNAUTHORIZED = "unauthorized";
    private static final String ERROR_FORBIDDEN = "forbidden";

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());   // public key only
    }

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:" + DEFAULT_AUTH_BASE_URL + "}") String baseUrl) {
        return new AuthClient(baseUrl);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier, Environment env) throws Exception {
        // The H2 console (arbitrary SQL over the inventory table) is opened ONLY under the
        // 'dev' profile — matching application-dev.yml which is the only place the console is
        // enabled. In every other profile /h2-console/** is authenticated like any other path,
        // and the frame-options relaxation it needs is not applied.
        boolean devConsole = env.acceptsProfiles(Profiles.of("dev"));
        http
            // CSRF DISABLED for this console (per operator request, local demo).
            // NOTE (security): CSRF protection is what stops a malicious page from driving a
            // restock on a logged-in supplier's behalf. It is off here to unblock the demo; the
            // JWT still lives in a SameSite=Strict cookie which blocks the classic cross-site POST,
            // but re-enable proper CSRF (a stable, non-rotating token — the rotation caused by
            // STATELESS + per-request re-auth is the bug that made the tokened version fail) before
            // any non-local deployment. The JSON /api/** surface is Bearer-authed and never used a token.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(PUBLIC_MATCHERS).permitAll();
                // GET availability is public — declared BEFORE the /api/inventory/** rule so the
                // more specific matcher wins (Spring evaluates matchers in order).
                auth.requestMatchers(org.springframework.http.HttpMethod.GET, AVAILABILITY_MATCHER).permitAll();
                if (devConsole) {
                    auth.requestMatchers(new AntPathRequestMatcher("/h2-console/**")).permitAll();
                }
                auth.requestMatchers(INVENTORY_MATCHERS).hasAnyRole(ROLE_SUPPLIER, ROLE_ADMIN)
                    .anyRequest().authenticated();
            })
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    if (req.getRequestURI().startsWith(API_PREFIX)) {
                        writeJsonStatus(res, jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, ERROR_UNAUTHORIZED);
                    } else {
                        res.sendRedirect(REDIRECT_LOGIN);
                    }
                })
                .accessDeniedHandler((req, res, ex) -> {
                    if (req.getRequestURI().startsWith(API_PREFIX)) {
                        writeJsonStatus(res, jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, ERROR_FORBIDDEN);
                    } else {
                        res.sendRedirect(REDIRECT_FORBIDDEN);
                    }
                }))
            .addFilterBefore(new AuthJwtFilter(verifier, com.petstore.inventory.web.InventoryLoginController.JWT_COOKIE),
                    UsernamePasswordAuthenticationFilter.class);
        if (devConsole) {
            http.headers(h -> h.frameOptions(f -> f.sameOrigin()));   // H2 console renders in frames
        }
        return http.build();
    }

    private static void writeJsonStatus(jakarta.servlet.http.HttpServletResponse res,
                                        int status, String error) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":\"" + error + "\",\"status\":" + status + "}");
    }
}
