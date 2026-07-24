package com.petstore.auth.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the auth-service public key bundled in this library
 * ({@code petstore-auth-public.pem} on the classpath), so verifier services can
 * build a {@link JwtVerifier} with zero configuration:
 *
 * <pre>{@code
 *   var verifier = new JwtVerifier(AuthPublicKey.bundled());
 *   http.addFilterBefore(new AuthJwtFilter(verifier), ...);
 * }</pre>
 *
 * <p>Only the PUBLIC key ships here — never the signing key.
 */
public final class AuthPublicKey {

    private static final String RESOURCE = "petstore-auth-public.pem";

    private AuthPublicKey() {
    }

    public static java.security.PublicKey bundled() {
        try (InputStream in = AuthPublicKey.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE + " on classpath");
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return PemKeys.rsaPublicKey(pem);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }
}
