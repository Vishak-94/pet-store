package com.petstore.security;

import org.springframework.security.core.Authentication;

/**
 * Reads the signed-in shopper's <b>stable customer userId</b> off the Spring Security
 * {@link Authentication}. {@link CustomerServiceAuthProvider} stashes the userId (the
 * customer-service key) on {@code getDetails()} at login, distinct from the username on
 * {@code getName()}; this is the single place that decodes that contract so the
 * {@code getDetails() instanceof String ? … : getName()} idiom isn't copy-pasted across
 * every controller (a DRY seam — change the auth encoding here once, not in five places).
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    /**
     * The stable customer userId for {@code auth}: the value {@link CustomerServiceAuthProvider}
     * put on {@code getDetails()}, falling back to the username when it isn't present (e.g. a
     * test or a differently-shaped principal).
     */
    public static String userId(Authentication auth) {
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof String uid ? uid : auth.getName();
    }
}
