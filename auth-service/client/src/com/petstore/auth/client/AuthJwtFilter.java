package com.petstore.auth.client;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ready-to-wire, verify-only Spring Security filter. Extracts a token (Bearer
 * header OR a {@code jwt} cookie so browser UIs work), verifies it with the
 * {@link JwtVerifier} (public key), and populates the SecurityContext with the
 * user's roles as {@code ROLE_*} authorities. Never mints tokens.
 *
 * <p>Importing services just register this filter — no per-service JWT code.
 */
public class AuthJwtFilter extends OncePerRequestFilter {

    /** Standard bearer-token header and its scheme prefix (RFC 6750). */
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** Cookie name browser UIs use to carry the token (so HTML clients need no header). */
    public static final String JWT_COOKIE = "jwt";

    /** Authority prefix Spring Security expects for role-based checks ({@code ROLE_ADMIN} etc.). */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtVerifier verifier;

    public AuthJwtFilter(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(req);
        if (token != null) {
            try {
                AuthClaims claims = verifier.verify(token);
                var authorities = claims.roles().stream()
                        .map(r -> new SimpleGrantedAuthority(ROLE_PREFIX + r)).toList();
                var auth = new UsernamePasswordAuthenticationToken(claims.username(), token, authorities);
                auth.setDetails(claims);   // downstream can read userId/roles if needed
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                SecurityContextHolder.clearContext();   // invalid/expired → anonymous
            }
        }
        chain.doFilter(req, res);
    }

    private static String extractToken(HttpServletRequest req) {
        String h = req.getHeader(AUTH_HEADER);
        if (h != null && h.startsWith(BEARER_PREFIX)) {
            return h.substring(BEARER_PREFIX.length());
        }
        if (req.getCookies() != null) {
            for (var c : req.getCookies()) {
                if (JWT_COOKIE.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
