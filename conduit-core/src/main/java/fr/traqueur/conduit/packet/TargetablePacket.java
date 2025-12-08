package fr.traqueur.conduit.packet;

import fr.traqueur.conduit.core.Conduit;

/**
 * Interface for packets that can be sent to a specific target instance (unicast).
 * 
 * <p>Example:</p>
 * <pre>
 * public record PlayerKickPacket(UUID playerId) implements TargetablePacket {}
 * 
 * // Send to specific server instance
 * new PlayerKickPacket(uuid).sendTo("server-lobby-1");
 * </pre>
 *
 * @author Traqueur
 */
public interface TargetablePacket extends Packet {

    default void send() {
        throw new UnsupportedOperationException(
                "Use sendTo(String targetId) for targetable packets. Regular send() is not supported."
        );
    }

    /**
     * Sends this packet to a specific target instance (unicast).
     * 
     * @param targetId the unique identifier of the target instance
     */
    default void sendTo(String targetId) {
        Conduit.getInstance().sendTo(this, targetId);
    }
}