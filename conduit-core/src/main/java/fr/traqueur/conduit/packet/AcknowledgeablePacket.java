package fr.traqueur.conduit.packet;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.Conduit;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for packets that require acknowledgment responses (broadcast with ACK).
 * 
 * <p>Example:</p>
 * <pre>
 * public record ConfigReloadPacket() implements AcknowledgeablePacket {}
 * 
 * // Send and wait for acknowledgment
 * new ConfigReloadPacket()
 *     .sendWithAck()
 *     .thenAccept(ack -> {
 *         if (ack.success()) {
 *             System.out.println("Config reloaded!");
 *         }
 *     });
 * </pre>
 *
 * @author Traqueur
 */
public interface AcknowledgeablePacket extends Packet {
    
    /**
     * Regular send is not supported for acknowledgeable packets.
     * Use {@link #sendWithAck()} instead.
     *
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    default void send() {
        throw new UnsupportedOperationException(
            "Use sendWithAck() for acknowledgeable packets. Regular send() is not supported."
        );
    }
    
    /**
     * Sends this packet via broadcast and waits for an acknowledgment response.
     * Default timeout is 5000ms (5 seconds).
     * 
     * @return a CompletableFuture containing the acknowledgment response
     */
    default CompletableFuture<AckResponse> sendWithAck() {
        return sendWithAck(5000L);
    }
    
    /**
     * Sends this packet via broadcast and waits for an acknowledgment response with custom timeout.
     * 
     * @param timeoutMs the timeout in milliseconds
     * @return a CompletableFuture containing the acknowledgment response
     */
    default CompletableFuture<AckResponse> sendWithAck(long timeoutMs) {
        return Conduit.getInstance().sendWithAck(this, timeoutMs);
    }
}