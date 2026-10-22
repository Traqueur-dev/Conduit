package fr.traqueur.conduit.security;

import fr.traqueur.conduit.core.PacketEnvelope;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks HMAC-SHA256 signatures against a fixed table of {@code keyId → secret}. An unknown key
 * id is refused. Compose it with the application's rules through {@link #and(EnvelopePolicy)}.
 * The secrets never appear in {@link #toString()}.
 *
 * @author Traqueur
 */
public final class HmacVerifier implements EnvelopeVerifier {

    private final Map<String, SecretKeySpec> keys;
    private final Map<String, ThreadLocal<Mac>> macs;

    private HmacVerifier(Map<String, byte[]> secrets) {
        Map<String, SecretKeySpec> k = new HashMap<>();
        Map<String, ThreadLocal<Mac>> m = new HashMap<>();
        secrets.forEach((id, secret) -> {
            SecretKeySpec key = HmacSigner.newKey(secret);
            k.put(id, key);
            m.put(id, ThreadLocal.withInitial(() -> HmacSigner.newMac(key)));
        });
        this.keys = Map.copyOf(k);
        this.macs = Map.copyOf(m);
    }

    /**
     * @param secrets the known keys, {@code keyId → secret} (copied)
     * @return the verifier
     */
    public static HmacVerifier sha256(Map<String, byte[]> secrets) {
        return new HmacVerifier(secrets);
    }

    @Override
    public Verdict verify(String keyId, byte[] data, byte[] signature, PacketEnvelope envelope) {
        ThreadLocal<Mac> mac = macs.get(keyId);
        if (mac == null) {
            return Verdict.refuse("unknown key id " + keyId);
        }
        byte[] expected = mac.get().doFinal(data);
        // Constant time: a byte-by-byte early exit would leak how much of a forgery was right.
        return MessageDigest.isEqual(expected, signature)
                ? Verdict.accept()
                : Verdict.refuse("bad signature for key id " + keyId);
    }

    @Override
    public String toString() {
        return "HmacVerifier[keyIds=" + keys.keySet() + ", HmacSHA256]";
    }
}
