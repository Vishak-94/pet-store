package com.petstore.customer.security;

import com.petstore.auth.client.AuthClient;
import com.petstore.auth.client.AuthJwtFilter;
import com.petstore.auth.client.AuthPublicKey;
import com.petstore.auth.client.JwtVerifier;
import com.petstore.customer.client.CustomerServiceEndpoints;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

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
    SecurityFilterChain filterChain(HttpSecurity http, JwtVerifier verifier, Environment env) throws Exception {
        // The H2 console (arbitrary SQL over the PII/card table) is opened ONLY under the
        // 'dev' profile — matching application-dev.yml which is the only place the console
        // is enabled. In every other profile the /h2-console/** path is authenticated like
        // any other, and the frame-options relaxation it needs is not applied.
        boolean devConsole = env.acceptsProfiles(org.springframework.core.env.Profiles.of("dev"));
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(CustomerServiceEndpoints.REGISTER, "/actuator/**").permitAll();
                if (devConsole) {
                    auth.requestMatchers(new AntPathRequestMatcher("/h2-console/**")).permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(new AuthJwtFilter(verifier), UsernamePasswordAuthenticationFilter.class);
        if (devConsole) {
            http.headers(h -> h.frameOptions(f -> f.sameOrigin()));
        }
        return http.build();
    }
}
