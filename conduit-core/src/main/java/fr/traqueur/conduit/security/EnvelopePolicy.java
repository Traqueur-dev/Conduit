package fr.traqueur.conduit.security;

import fr.traqueur.conduit.core.PacketEnvelope;

/**
 * Application rule applied to an envelope whose signature is already valid: who may send what.
 *
 * <pre>
 * EnvelopePolicy policy = (keyId, env) -&gt;
 *     !"si:command".equals(env.wireName()) || keyId.equals("reconciler")
 *         ? Verdict.accept()
 *         : Verdict.refuse(keyId + " may not send si:command");
 * </pre>
 *
 * @author Traqueur
 * @see EnvelopeVerifier#and(EnvelopePolicy)
 */
@FunctionalInterface
public interface EnvelopePolicy {

    /**
     * @param keyId    the verified key id
     * @param envelope the envelope
     * @return the verdict
     */
    EnvelopeVerifier.Verdict check(String keyId, PacketEnvelope envelope);
}
