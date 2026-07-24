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
 * ADMIN; {@code /checkout}, {@code /api/checkout}, and {@code /customer} require
 * authentication (both checkout endpoints create orders).
 */
@Configuration
public class SecurityConfig {

    /** AuthenticationManager backed solely by the customer-service provider. */
    @Bean
    AuthenticationManager authenticationManager(CustomerServiceAuthProvider provider) {
        return new ProviderManager(provider);
    }

    /** Applies the customer's stored preferredLanguage to the session locale on sign-on. */
    @Bean
    SignOnLocaleSuccessHandler signOnLocaleSuccessHandler(
            com.petstore.customer.client.CustomerServiceClient customerClient,
            org.springframework.web.servlet.LocaleResolver localeResolver) {
        return new SignOnLocaleSuccessHandler(customerClient, localeResolver);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AuthenticationManager authManager,
                                    SignOnLocaleSuccessHandler successHandler) throws Exception {
        http
            .authenticationManager(authManager)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/category", "/product", "/item", "/search",
                        "/cart", "/cart/**", "/register-form", "/login",
                        "/orders/**", "/css/**").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/checkout", "/api/checkout", "/customer").authenticated()
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginPage("/login").permitAll()
                // applies the customer's stored preferredLanguage on sign-on (legacy SignOnNotifier),
                // then redirects home — replaces the old defaultSuccessUrl("/", true).
                .successHandler(successHandler))
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/?loggedout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    new AntPathRequestMatcher("/checkout"),
                    new AntPathRequestMatcher("/api/checkout"),
                    new AntPathRequestMatcher("/cart/**"),
                    new AntPathRequestMatcher("/admin/**")))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }
}
