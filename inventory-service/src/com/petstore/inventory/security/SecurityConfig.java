package com.petstore.inventory.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Verify-only security for the supplier service. Tokens are minted by auth-service
 * and verified here with the bundled PUBLIC key via the auth-client
 * {@link AuthJwtFilter} — inventory-service holds NO credentials and cannot issue
 * tokens. Inventory view + restock → SUPPLIER (ADMIN also allowed).
 */
@Configuration
public class SecurityConfig {

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());   // public key only
    }

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:http://localhost:8086}") String baseUrl) {
        return new AuthClient(baseUrl);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/inventory/login", "/inventory/logout").permitAll()
                .requestMatchers("/inventory/**", "/api/inventory/**").hasAnyRole("SUPPLIER", "ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        writeJsonStatus(res, jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
                    } else {
                        res.sendRedirect("/inventory/login");
                    }
                })
                .accessDeniedHandler((req, res, ex) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        writeJsonStatus(res, jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "forbidden");
                    } else {
                        res.sendRedirect("/inventory/login?forbidden");
                    }
                }))
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeJsonStatus(jakarta.servlet.http.HttpServletResponse res,
                                        int status, String error) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + error + "\",\"status\":" + status + "}");
    }
}
