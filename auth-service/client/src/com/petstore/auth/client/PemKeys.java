package com.petstore.auth.client;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads an RSA public key from a PEM string ({@code -----BEGIN PUBLIC KEY-----}).
 * Verifier services use this to build the {@link JwtVerifier} — they only ever
 * hold the PUBLIC key, so they can verify tokens but physically cannot mint them.
 */
public final class PemKeys {

    private PemKeys() {
    }

    public static PublicKey rsaPublicKey(String pem) {
        String base64 = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid RSA public key PEM", e);
        }
    }
}
