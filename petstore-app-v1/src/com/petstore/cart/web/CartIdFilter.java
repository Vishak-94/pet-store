package com.petstore.cart.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Issues and resolves the anonymous cart id — the storefront's equivalent of a
 * {@code JSESSIONID}, but dedicated to the cart so it works for logged-out
 * shoppers (legacy allowed add-to-cart without sign-in) and survives login.
 *
 * <p>On each request: if a {@value #COOKIE} cookie is present, its value is the
 * cart id; otherwise a fresh 128-bit {@link SecureRandom} id is minted and set
 * as a cookie. Either way the id is stashed on the request so
 * {@link com.petstore.cart.service.CartService} can read it and delegate to
 * cart-service. The id is never generated client-side (that would allow cart
 * hijacking) — same rule as a session id.
 */
@Component
@Order(1)
public class CartIdFilter extends OncePerRequestFilter {

    public static final String COOKIE = "cartId";
    public static final String REQUEST_ATTR = "cartId";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String cartId = readCookie(request);
        if (cartId == null) {
            cartId = newCartId();
            Cookie cookie = new Cookie(COOKIE, cartId);
            cookie.setPath("/");
            cookie.setHttpOnly(true);          // JS can't read it (anti-hijack)
            cookie.setMaxAge(-1);              // session cookie: cleared when browser closes
            response.addCookie(cookie);
        }
        request.setAttribute(REQUEST_ATTR, cartId);
        chain.doFilter(request, response);
    }

    private static String readCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        return null;
    }

    /** 128 bits of CSPRNG randomness, hex-encoded — unique and unguessable. */
    private static String newCartId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
