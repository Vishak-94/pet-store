package com.petstore.order.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * Encrypts / decrypts the checkout order-id token exchanged with the browser (AES-256/GCM).
 *
 * <p>The plaintext is a server-minted order id; the browser only ever sees the ciphertext
 * (stored in the checkout form's hidden field) and echoes it back on submit. Authenticated
 * encryption (GCM) makes the token opaque and tamper-evident: a modified ciphertext fails the
 * auth tag and {@link #decrypt} returns empty rather than a bogus id. Idempotency itself is
 * still enforced by the {@code IdempotencyKeyStore} membership check — the cipher just keeps
 * the id opaque on the wire.
 *
 * <p>Key: base64 AES key from {@code checkout.idempotency.cipher-key} if set, else a random
 * key generated at startup (dev default — tokens don't survive a restart, which is fine since
 * the in-memory reservation store doesn't either). Set the property to a stable 256-bit key
 * in any real deployment.
 */
@Component
public class OrderKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(OrderKeyCipher.class);
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;         // 96-bit nonce (GCM standard)
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public OrderKeyCipher(@Value("${checkout.idempotency.cipher-key:}") String configuredKey) {
        this.key = resolveKey(configuredKey);
    }

    private static SecretKey resolveKey(String configuredKey) {
        if (configuredKey != null && !configuredKey.isBlank()) {
            byte[] raw = Base64.getDecoder().decode(configuredKey.trim());
            return new SecretKeySpec(raw, "AES");
        }
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256);
            log.warn("No checkout.idempotency.cipher-key configured — generated an ephemeral AES key "
                    + "(checkout tokens will not survive a restart). Set the property for a stable key.");
            return kg.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialise checkout order-key cipher", e);
        }
    }

    /** Encrypt the plaintext id → base64url(iv || ciphertext+tag). Never returns null. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt checkout token", e);
        }
    }

    /**
     * Decrypt a token produced by {@link #encrypt}. Returns empty (never throws) on any tampered,
     * truncated, or otherwise invalid token — the caller treats that as "no valid reservation".
     */
    public Optional<String> decrypt(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] in = Base64.getUrlDecoder().decode(token.trim());
            if (in.length <= IV_BYTES) {
                return Optional.empty();
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(in, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return Optional.of(new String(pt, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // bad base64, wrong key, or failed auth tag (tampering) → not a valid token
            return Optional.empty();
        }
    }
}
