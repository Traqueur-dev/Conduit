package fr.traqueur.conduit.security;

/**
 * Signs outgoing envelopes.
 *
 * <p>The signature covers the whole encoded envelope up to the signature itself: version,
 * timestamp, packet id, sender, namespace (transport channel prefix), channel, unicast target,
 * wire name, reply channel and compressed payload. See {@link fr.traqueur.conduit.core.PacketEnvelope}.</p>
 *
 * @author Traqueur
 */
public interface EnvelopeSigner {

    /**
     * Identifies the key that signs, so the verifier knows which key to check against
     * (e.g. {@code "paper-lobby-1"}). Travels in clear in the envelope.
     *
     * @return the key id
     */
    String keyId();

    /**
     * Signs bytes. Must be thread-safe.
     *
     * @param data the bytes to sign
     * @return the signature
     */
    byte[] sign(byte[] data);
}
