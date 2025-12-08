package fr.traqueur.conduit.packet;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.core.PacketMeta;

/**
 * Base interface for all packets in the Conduit system.
 * Implementations should be records with all necessary data fields.
 * 
 * <p>Example:</p>
 * <pre>
 * public record PlayerJoinPacket(UUID playerId, String playerName) implements Packet {
 *     // No additional code needed!
 * }
 * </pre>
 * 
 * <p>By default, packets are sent to the default channel. 
 * Use {@link PacketMeta} annotation to specify a custom channel:</p>
 * <pre>
 * {@literal @}PacketMeta(channel = "events")
 * public record EventStartPacket(String eventId) implements Packet {}
 * </pre>
 *
 * @author Traqueur
 */
public interface Packet {
    
    /**
     * Sends this packet via broadcast (all instances listening on the channel will receive it).
     * Uses the channel specified in {@link PacketMeta} annotation, or the default channel if not present.
     */
    default void send() {
        Conduit.getInstance().send(this);
    }
}