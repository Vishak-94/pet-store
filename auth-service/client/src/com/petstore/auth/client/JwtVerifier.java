package com.petstore.auth.client;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.security.PublicKey;
import java.util.List;

/**
 * Verifies RS256 tokens issued by auth-service, using ONLY the public key. It can
 * check a token's signature but has no way to sign one — so importing services are
 * pure verifiers and cannot forge identities. Returns the decoded {@link AuthClaims}.
 */
public class JwtVerifier {

    private final PublicKey publicKey;

    public JwtVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    /** Convenience: build from a PEM public-key string. */
    public static JwtVerifier fromPem(String publicKeyPem) {
        return new JwtVerifier(PemKeys.rsaPublicKey(publicKeyPem));
    }

    /**
     * Verify signature + expiry and return the claims. Throws
     * {@link io.jsonwebtoken.JwtException} if invalid/expired/tampered.
     */
    @SuppressWarnings("unchecked")
    public AuthClaims verify(String token) {
        Claims c = Jwts.parser().verifyWith(publicKey).build()
                .parseSignedClaims(token).getPayload();
        List<String> roles = c.get(AuthClaims.CLAIM_ROLES, List.class);
        return new AuthClaims(
                c.get(AuthClaims.CLAIM_USER_ID, String.class),
                c.getSubject(),
                roles == null ? List.of() : roles);
    }
}
