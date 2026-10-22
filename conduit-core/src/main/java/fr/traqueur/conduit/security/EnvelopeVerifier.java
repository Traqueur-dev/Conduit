package fr.traqueur.conduit.security;

import fr.traqueur.conduit.core.PacketEnvelope;

/**
 * Decides whether an incoming envelope is accepted.
 *
 * <p>Called for every packet and every ACK response once a verifier is configured on the
 * {@link fr.traqueur.conduit.core.Conduit}. An unsigned envelope never reaches it: it is refused
 * before. The verifier both checks the signature and applies the application's policy — who may
 * send which wire name, on which channel, for whom. Must be thread-safe.</p>
 *
 * @author Traqueur
 */
@FunctionalInterface
public interface EnvelopeVerifier {

    /**
     * Verifies an envelope.
     *
     * @param keyId     the key id the sender claims
     * @param data      the signed bytes
     * @param signature the signature
     * @param envelope  the decoded envelope (sender, wire name, channel, target...)
     * @return the verdict
     */
    Verdict verify(String keyId, byte[] data, byte[] signature, PacketEnvelope envelope);

    /**
     * Chains a policy after this verifier: the policy only runs when this verifier accepts.
     *
     * @param policy the application policy
     * @return a verifier that accepts only when both accept
     */
    default EnvelopeVerifier and(EnvelopePolicy policy) {
        return (keyId, data, signature, envelope) -> {
            Verdict verdict = verify(keyId, data, signature, envelope);
            return verdict instanceof Verdict.Accepted ? policy.check(keyId, envelope) : verdict;
        };
    }

    /**
     * The outcome of a verification.
     */
    sealed interface Verdict {

        /**
         * @return the accepted verdict
         */
        static Verdict accept() {
            return Accepted.INSTANCE;
        }

        /**
         * @param reason why the envelope is refused (logged, never sent back)
         * @return a refused verdict
         */
        static Verdict refuse(String reason) {
            return new Refused(reason);
        }

        /** The envelope is accepted. */
        record Accepted() implements Verdict {
            private static final Accepted INSTANCE = new Accepted();
        }

        /**
         * The envelope is refused.
         *
         * @param reason why
         */
        record Refused(String reason) implements Verdict {}
    }
}
