package com.petstore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Spring Security configuration for the monolith storefront.
 *
 * <p>Authentication is DELEGATED to customer-service via
 * {@link CustomerServiceAuthProvider} — the monolith holds no local credentials
 * or UserDetailsService. Form login validates against customer-service; the
 * resulting session carries the JWT + roles. {@code /logout} clears the session
 * (and the session-scoped cart), replacing the legacy signoff.do.
 *
 * <p>Access rules: public catalog/cart/register/login; {@code /admin/**} needs
 * ADMIN; {@code /checkout} authenticated.
 */
@Configuration
public class SecurityConfig {

    /** AuthenticationManager backed solely by the customer-service provider. */
    @Bean
    AuthenticationManager authenticationManager(CustomerServiceAuthProvider provider) {
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationManager authManager) throws Exception {
        http
            .authenticationManager(authManager)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/category", "/product", "/item", "/search",
                        "/cart", "/cart/**", "/register-form", "/login",
                        "/orders/**", "/css/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/checkout").authenticated()
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginPage("/login").permitAll()
                .defaultSuccessUrl("/", true))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/?loggedout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    new AntPathRequestMatcher("/checkout"),
                    new AntPathRequestMatcher("/cart/**"),
                    new AntPathRequestMatcher("/admin/**")))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }
}
