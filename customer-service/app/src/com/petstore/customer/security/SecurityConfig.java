package com.petstore.customer.security;

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
 * Verify-only security. customer-service owns customer DOMAIN data but no
 * credentials — auth-service mints tokens; here we only verify them with the
 * bundled public key via the auth-client {@link AuthJwtFilter}. Registration is
 * public (it provisions the credential in auth-service); reading/updating a
 * customer needs a valid token.
 */
@Configuration
public class SecurityConfig {

    @Bean
    JwtVerifier jwtVerifier() {
        return new JwtVerifier(AuthPublicKey.bundled());
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
                .requestMatchers("/register", "/h2-console/**", "/actuator/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class)
            .headers(h -> h.frameOptions(f -> f.sameOrigin()));
        return http.build();
    }
}
