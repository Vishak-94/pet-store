package com.petstore.opc.security;

import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Verify-only security. The admin facade (/api/orders/**) is ADMIN-only; the admin
 * console (admin-office-service) forwards the acting admin's JWT, which this
 * service verifies with the bundled public key (auth-client). OPC holds no
 * credentials and issues no tokens.
 */
@Configuration
public class SecurityConfig {

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/error").permitAll()
                .requestMatchers("/api/orders/**", "/api/sales/**", "/api/sales").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                    res.sendError(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((req, res, ex) ->
                    res.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN)))
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
