package com.petstore.auth.client;

import java.util.List;

/**
 * The verified identity carried by a token — the subset of JWT claims services
 * care about. This is NOT stored anywhere: it's decoded from the token per
 * request. {@code userId} is the stable opaque id (subject); {@code roles} drives
 * authorization.
 *
 * <p>The claim-name constants below are the wire contract: the issuer (auth-service
 * {@code JwtIssuer}) stamps them and the {@link JwtVerifier} reads them back. They
 * live here — in the client both sides can see — so the two ends can never drift.
 * They are contract literals, so they stay as constants and are never externalized
 * to config.
 */
public record AuthClaims(String userId, String username, List<String> roles) {

    /** JWT claim carrying the stable opaque user id ({@code sub} carries the username). */
    public static final String CLAIM_USER_ID = "uid";

    /** JWT claim carrying the list of role strings ({@code USER}/{@code SUPPLIER}/{@code ADMIN}). */
    public static final String CLAIM_ROLES = "roles";
}
