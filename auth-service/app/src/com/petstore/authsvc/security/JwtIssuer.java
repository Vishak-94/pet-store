package com.petstore.authsvc.security;

import com.petstore.auth.client.AuthClaims;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Mints RS256 tokens with the RSA PRIVATE key — held ONLY by auth-service. No
 * other service has this key, so no other service can issue tokens. The matching
 * public key ships in auth-client for verification.
 */
@Service
public class JwtIssuer {

    private final PrivateKey privateKey;
    private final long ttlMillis;

    public JwtIssuer(@Value("${auth.jwt.private-key}") Resource privateKeyPem,
                     @Value("${auth.jwt.ttl-seconds:3600}") long ttlSeconds) {
        this.privateKey = loadPrivateKey(privateKeyPem);
        this.ttlMillis = ttlSeconds * 1000;
    }

    /** Mint a signed token carrying the stable userId + roles. */
    public String issue(String username, String userId, List<String> roles) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim(AuthClaims.CLAIM_USER_ID, userId)
                .claim(AuthClaims.CLAIM_ROLES, roles)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(privateKey)     // RS256 (inferred from RSA private key)
                .compact();
    }

    private static PrivateKey loadPrivateKey(Resource pem) {
        try (InputStream in = pem.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(content);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load RSA private key", e);
        }
    }
}
