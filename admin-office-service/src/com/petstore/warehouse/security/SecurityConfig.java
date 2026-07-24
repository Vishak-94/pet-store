package com.petstore.warehouse.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import com.petstore.opc.client.OrderProcessingClient;
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

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());   // public key only
    }

    @Bean
    AuthClient authClient(@Value("${services.auth.base-url:http://localhost:8086}") String baseUrl) {
        return new AuthClient(baseUrl);
    }

    @Bean
    OrderProcessingClient orderProcessingClient(
            @Value("${services.opc.base-url:http://localhost:8088}") String baseUrl) {
        return new OrderProcessingClient(baseUrl);   // calls the OPC admin facade
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/warehouse/login", "/warehouse/logout").permitAll()
                .requestMatchers("/warehouse/orders/**", "/api/orders/**", "/warehouse/users", "/warehouse/users/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        writeJsonStatus(res, jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED, "unauthorized");
                    } else {
                        res.sendRedirect("/warehouse/login");
                    }
                })
                .accessDeniedHandler((req, res, ex) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        writeJsonStatus(res, jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN, "forbidden");
                    } else {
                        res.sendRedirect("/warehouse/login?forbidden");
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
