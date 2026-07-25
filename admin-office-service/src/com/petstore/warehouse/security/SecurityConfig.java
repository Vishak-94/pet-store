package com.petstore.warehouse.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import com.petstore.opc.client.OrderProcessingClient;
import com.petstore.warehouse.config.ResilientRestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Verify-only security for the admin service. Tokens are minted by auth-service
 * and verified here with the bundled PUBLIC key via the auth-client
 * {@link AuthJwtFilter} — warehouse-service holds NO credentials and cannot issue
 * tokens. Order approval + admin management → ADMIN only.
 */
@Configuration
public class SecurityConfig {

    /** Default downstream base URLs when the {@code services.*.base-url} properties are unset. */
    private static final String DEFAULT_AUTH_BASE_URL = "http://localhost:8086";
    private static final String DEFAULT_OPC_BASE_URL = "http://localhost:8088";
    /** Resilience4j instance names (used in logs/metrics) for the two downstreams. */
    private static final String CB_AUTH = "auth-service";
    private static final String CB_OPC = "order-processing-service";

    /** Public paths: health/metrics probes and the login/logout endpoints themselves. */
    private static final String[] PUBLIC_MATCHERS = {"/actuator/**", "/warehouse/login", "/warehouse/logout"};
    /** ADMIN-only surface: order console/API, sales, and admin user management. */
    private static final String[] ADMIN_MATCHERS = {
            "/warehouse/orders/**", "/api/orders/**", "/api/sales", "/api/sales/**",
            "/warehouse/users", "/warehouse/users/**"};
    /** Role (without Spring's {@code ROLE_} prefix) required for the admin surface. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** JSON API prefix — requests under it get a JSON error, others a redirect to login. */
    private static final String API_PREFIX = "/api/";
    private static final String REDIRECT_LOGIN = "/warehouse/login";
    private static final String REDIRECT_FORBIDDEN = "/warehouse/login?forbidden";
    /** Error codes echoed in the JSON body for API auth failures. */
    private static final String ERROR_UNAUTHORIZED = "unauthorized";
    private static final String ERROR_FORBIDDEN = "forbidden";

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());   // public key only
    }

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:" + DEFAULT_AUTH_BASE_URL + "}") String baseUrl) {
        return new AuthClient(ResilientRestClient.forService(CB_AUTH, baseUrl));
    }

    @Bean
    OrderProcessingClient orderProcessingClient(
            @Value("${services.opc.base-url:" + DEFAULT_OPC_BASE_URL + "}") String baseUrl) {
        // calls the OPC admin facade, guarded by a circuit breaker + GET-only retry
        return new OrderProcessingClient(ResilientRestClient.forService(CB_OPC, baseUrl));
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_MATCHERS).permitAll()
                .requestMatchers(ADMIN_MATCHERS).hasRole(ROLE_ADMIN)
                .anyRequest().authenticated())
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
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJsonStatus(jakarta.servlet.http.HttpServletResponse res,
                                        int status, String error) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":\"" + error + "\",\"status\":" + status + "}");
    }
}
