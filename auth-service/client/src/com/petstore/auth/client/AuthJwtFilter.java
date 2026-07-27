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

    /**
     * Default cookie name browser UIs use to carry the token (so HTML clients need no header).
     * Services that share {@code localhost} with another UI should give this a service-specific
     * name (see {@link #AuthJwtFilter(JwtVerifier, String)}) so their cookies cannot collide —
     * cookies are keyed by host+path, NOT port, and this reader takes the FIRST cookie of the
     * configured name, so a same-named cookie from a sibling service could otherwise shadow ours.
     */
    public static final String JWT_COOKIE = "jwt";

    /** Authority prefix Spring Security expects for role-based checks ({@code ROLE_ADMIN} etc.). */
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtVerifier verifier;
    /** The cookie name this filter reads the token from (header Bearer is always honoured too). */
    private final String cookieName;

    /** Read the token from the default {@code jwt} cookie (or the Bearer header). */
    public AuthJwtFilter(JwtVerifier verifier) {
        this(verifier, JWT_COOKIE);
    }

    /**
     * Read the token from a service-specific cookie name (or the Bearer header). Use a distinct
     * name (e.g. {@code jwt-inventory}, {@code jwt-warehouse}) when several UIs run on the same
     * host so a sibling's identically-named cookie can't shadow this service's token.
     *
     * @param cookieName cookie name to read the token from; falls back to {@code jwt} if blank
     */
    public AuthJwtFilter(JwtVerifier verifier, String cookieName) {
        this.verifier = verifier;
        this.cookieName = (cookieName == null || cookieName.isBlank()) ? JWT_COOKIE : cookieName;
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

    private String extractToken(HttpServletRequest req) {
        String h = req.getHeader(AUTH_HEADER);
        if (h != null && h.startsWith(BEARER_PREFIX)) {
            return h.substring(BEARER_PREFIX.length());
        }
        if (req.getCookies() != null) {
            for (var c : req.getCookies()) {
                if (cookieName.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return null;
    }
}
