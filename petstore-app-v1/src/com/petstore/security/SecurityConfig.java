package com.petstore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Arrays;

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

    /** Public browse surface (catalog/cart/register/login/css) — no authentication required. */
    private static final String[] PUBLIC_MATCHERS = {
            "/", "/category", "/product", "/item", "/search",
            "/cart", "/cart/**", "/register-form", "/login",
            "/orders/**", "/css/**",
            // Same-origin stock proxy for the after-load stepper cap — public browse data
            // (mirrors /product, /item), degrades to 204 when inventory-service is unavailable.
            "/api/stock/**"};
    /** ADMIN-only surface (kept for parity with the legacy admin links). */
    private static final String ADMIN_MATCHERS = "/admin/**";
    /** Requires a signed-in customer — both checkout endpoints create orders. */
    private static final String[] AUTHENTICATED_MATCHERS = {
            "/checkout", "/api/checkout", "/pre-checkout", "/customer"};
    /** Form/AJAX POST paths exempted from CSRF (token-less programmatic posts). */
    private static final String[] CSRF_EXEMPT_MATCHERS = {
            "/checkout", "/api/checkout", "/pre-checkout", "/cart/**", "/admin/**"};
    /** Role (without Spring's {@code ROLE_} prefix) required for the admin surface. */
    private static final String ROLE_ADMIN = "ADMIN";

    /** Form-login / logout endpoints and the servlet session cookie cleared on logout. */
    private static final String LOGIN_PAGE = "/login";
    private static final String LOGOUT_URL = "/logout";
    private static final String LOGOUT_SUCCESS_URL = "/?loggedout";
    private static final String SESSION_COOKIE = "JSESSIONID";

    /** AuthenticationManager backed solely by the customer-service provider. */
    @Bean
    AuthenticationManager authenticationManager(CustomerServiceAuthProvider provider) {
        ProviderManager manager = new ProviderManager(provider);
        // KEEP the credential (the RS256 JWT) on the stored Authentication. ProviderManager
        // erases credentials by default, but this storefront deliberately holds the JWT as the
        // credential so downstream calls (checkout → OPC, customer-service) can forward it as a
        // Bearer token. Erasing it would leave getCredentials()==null → "Bearer null" → 401.
        manager.setEraseCredentialsAfterAuthentication(false);
        return manager;
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
                .requestMatchers(PUBLIC_MATCHERS).permitAll()
                .requestMatchers(ADMIN_MATCHERS).hasRole(ROLE_ADMIN)
                .requestMatchers(AUTHENTICATED_MATCHERS).authenticated()
                .anyRequest().permitAll())
            .formLogin(form -> form
                .loginPage(LOGIN_PAGE).permitAll()
                // applies the customer's stored preferredLanguage on sign-on (legacy SignOnNotifier),
                // then redirects home — replaces the old defaultSuccessUrl("/", true).
                .successHandler(successHandler))
            .logout(logout -> logout
                .logoutUrl(LOGOUT_URL)
                .logoutSuccessUrl(LOGOUT_SUCCESS_URL)
                .invalidateHttpSession(true)
                .deleteCookies(SESSION_COOKIE))
            .csrf(csrf -> csrf.ignoringRequestMatchers(
                    Arrays.stream(CSRF_EXEMPT_MATCHERS)
                            .map(AntPathRequestMatcher::new)
                            .toArray(AntPathRequestMatcher[]::new)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }
}
