package com.petstore.auth.client;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the verify-only contract: a token signed by a private key verifies with
 * its matching public key and yields the right claims, while a token signed by a
 * DIFFERENT key is rejected (i.e. a service holding only the public key cannot be
 * fooled by a forged token).
 */
class JwtVerifierTest {

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String sign(KeyPair kp, String user, String uid, List<String> roles) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(user).claim("uid", uid).claim("roles", roles)
                .issuedAt(new Date(now)).expiration(new Date(now + 60000))
                .signWith(kp.getPrivate()).compact();
    }

    @Test
    void verifies_validToken_andExtractsClaims() throws Exception {
        KeyPair kp = rsa();
        JwtVerifier verifier = new JwtVerifier(kp.getPublic());
        String token = sign(kp, "supplier", "u-3", List.of("SUPPLIER"));

        AuthClaims claims = verifier.verify(token);
        assertThat(claims.username()).isEqualTo("supplier");
        assertThat(claims.userId()).isEqualTo("u-3");
        assertThat(claims.roles()).containsExactly("SUPPLIER");
    }

    @Test
    void rejects_tokenSignedByDifferentKey() throws Exception {
        KeyPair issuer = rsa();
        KeyPair attacker = rsa();
        JwtVerifier verifier = new JwtVerifier(issuer.getPublic());   // trusts only issuer
        String forged = sign(attacker, "admin", "u-1", List.of("ADMIN"));

        assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void bundledPublicKey_loads() {
        assertThat(AuthPublicKey.bundled()).isNotNull();
        // and can build a verifier without error
        JwtVerifier v = new JwtVerifier(AuthPublicKey.bundled());
        assertThat(v).isNotNull();
    }
}
