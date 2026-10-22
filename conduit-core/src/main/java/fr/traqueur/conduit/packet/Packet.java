package fr.traqueur.conduit.packet;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.core.PacketMeta;

import java.util.concurrent.CompletableFuture;

/**
 * Base interface for all packets. Implementations should be records.
 *
 * <pre>
 * {@literal @}PacketMeta(channel = "events", wire = "events.start.v1")
 * public record EventStartPacket(String eventId) implements Packet {}
 * </pre>
 *
 * <p>The default methods go through the default instance ({@link Conduit#getInstance()}); with
 * several instances, or one built {@linkplain Conduit.ConduitBuilder#unregistered() unregistered},
 * call {@code conduit.send(packet)} instead.</p>
 *
 * @author Traqueur
 * @see PacketMeta
 */
public interface Packet {

    /**
     * Broadcasts this packet through the default instance.
     *
     * @return a future that completes once the transport published it
     * @throws IllegalStateException if there is no default instance
     */
    default CompletableFuture<Void> send() {
        return Conduit.getInstance().send(this);
    }
}
