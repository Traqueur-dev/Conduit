package fr.traqueur.conduit.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * HMAC-SHA256 signer ({@code javax.crypto}, no dependency). The secret never appears in
 * {@link #toString()}.
 *
 * @author Traqueur
 */
public final class HmacSigner implements EnvelopeSigner {

    static final String ALGORITHM = "HmacSHA256";

    private final String keyId;
    private final SecretKeySpec key;
    private final ThreadLocal<Mac> mac;

    private HmacSigner(String keyId, byte[] secret) {
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.key = newKey(secret);
        this.mac = ThreadLocal.withInitial(() -> newMac(key));
    }

    /**
     * Creates an HMAC-SHA256 signer.
     *
     * @param keyId  the key id sent with each envelope
     * @param secret the shared secret (copied; at least 32 bytes recommended)
     * @return the signer
     */
    public static HmacSigner sha256(String keyId, byte[] secret) {
        return new HmacSigner(keyId, secret);
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public byte[] sign(byte[] data) {
        return mac.get().doFinal(data);
    }

    static SecretKeySpec newKey(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length == 0) {
            throw new IllegalArgumentException("HMAC secret must not be empty");
        }
        return new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    static Mac newMac(SecretKeySpec key) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory on every Java platform: this is a broken JVM, not bad input.
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "HmacSigner[keyId=" + keyId + ", HmacSHA256]";
    }
}
