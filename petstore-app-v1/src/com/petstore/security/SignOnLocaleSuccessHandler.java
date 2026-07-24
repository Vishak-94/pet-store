package com.petstore.security;

import com.petstore.customer.client.CustomerDtos.CustomerView;
import com.petstore.customer.client.CustomerServiceClient;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.util.Locale;

/**
 * On successful sign-on, applies the customer's stored {@code preferredLanguage}
 * to the session locale — restoring the legacy {@code SignOnNotifier} behaviour
 * (it set the session/cart locale from the customer profile at login). The locale
 * drives both the UI message bundles and the locale-split catalog content.
 *
 * <p>Precedence: an explicit {@code ?lang=} on the login request wins (that override
 * is a migration-era addition handled by the LocaleChangeInterceptor); otherwise the
 * stored preference is applied, exactly as legacy did unconditionally at sign-on.
 */
public class SignOnLocaleSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SignOnLocaleSuccessHandler.class);

    private final CustomerServiceClient customerClient;
    private final LocaleResolver localeResolver;

    public SignOnLocaleSuccessHandler(CustomerServiceClient customerClient, LocaleResolver localeResolver) {
        this.customerClient = customerClient;
        this.localeResolver = localeResolver;
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);   // matches the old defaultSuccessUrl("/", true)
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        applyPreferredLanguage(request, response, authentication);
        super.onAuthenticationSuccess(request, response, authentication);
    }

    /** Sets the session locale from the customer's preferredLanguage (unless ?lang= overrode it). */
    private void applyPreferredLanguage(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        if (request.getParameter("lang") != null) {
            return;   // explicit override on the login request wins
        }
        Object token = authentication.getCredentials();   // the JWT set by CustomerServiceAuthProvider
        if (token == null) {
            return;
        }
        String userId = authentication.getDetails() instanceof String uid ? uid : authentication.getName();
        try {
            Locale locale = customerClient.getCustomer(userId, token.toString())
                    .map(SignOnLocaleSuccessHandler::preferredLocale)
                    .orElse(null);
            if (locale != null) {
                localeResolver.setLocale(request, response, locale);
            }
        } catch (org.springframework.web.client.RestClientException e) {
            log.warn("could not apply preferred locale for {} on sign-on: {}", userId, e.getMessage());
        }
    }

    /** Reads profile.preferredLanguage (e.g. "en_US") and parses it to a Locale; null if absent. */
    private static Locale preferredLocale(CustomerView view) {
        if (view.profile() == null) {
            return null;
        }
        Object lang = view.profile().get("preferredLanguage");
        if (lang == null || lang.toString().isBlank()) {
            return null;
        }
        // Legacy getLocaleFromString split on '_' (language_country); Pet Store locales are 2-part.
        String[] parts = lang.toString().split("_");
        return parts.length >= 2 ? new Locale(parts[0], parts[1]) : new Locale(parts[0]);
    }
}
