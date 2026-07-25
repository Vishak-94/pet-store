package com.petstore.web;

import com.petstore.cart.service.CartService;
import com.petstore.config.WebConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Injects model attributes shared by every HTML view:
 * <ul>
 *   <li>{@code cartCount} — so the nav-bar cart badge is correct on every page
 *       load (not just after a live JS update).</li>
 *   <li>{@code langSwitchBase} — the current request path plus its existing query
 *       parameters (minus any {@code lang}), ending in {@code lang=}, so the
 *       language switcher can append just the locale code without dropping other
 *       required params such as {@code id} on /product, /category and /item.</li>
 * </ul>
 *
 * <p>Scoped to {@code @Controller}s (annotations = Controller.class) so it does
 * NOT run for {@code @RestController} JSON endpoints.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class GlobalModelAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalModelAdvice.class);

    private final CartService cart;

    public GlobalModelAdvice(CartService cart) {
        this.cart = cart;
    }

    /**
     * The cart badge shown on EVERY page. It must never break page rendering, so any
     * failure resolving the count (e.g. the cart-id filter didn't run for this request)
     * degrades to 0 rather than propagating a 500 out of an unrelated page. The count
     * itself is now catalog-free (see {@link CartService#getCount}), so a catalog outage
     * no longer affects it either.
     */
    @ModelAttribute("cartCount")
    public int cartCount() {
        try {
            return cart.getCount();
        } catch (RuntimeException e) {
            log.debug("cartCount unavailable, defaulting badge to 0: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Base URL the language switcher appends a locale code to. Preserves the
     * current path and all query params except {@code lang}. Always ends in
     * {@code lang=} so the template does {@code ${langSwitchBase} + 'ja_JP'}.
     */
    @ModelAttribute("langSwitchBase")
    public String langSwitchBase() {
        // The locale param name is single-sourced in WebConfig (?lang= switch + cookie).
        final String param = WebConfig.LOCALE_PARAM;
        final String suffix = param + "=";                 // e.g. "lang="
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "?" + suffix;
        }
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return path + "?" + suffix;
        }
        // Keep every param except an existing lang so switching is idempotent.
        String preserved = Arrays.stream(query.split("&"))
                .filter(p -> !p.isBlank())
                .filter(p -> !p.equals(param) && !p.startsWith(suffix))
                .collect(Collectors.joining("&"));
        String sep = preserved.isEmpty() ? "?" : "?" + preserved + "&";
        return path + sep + suffix;
    }

    private static HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
