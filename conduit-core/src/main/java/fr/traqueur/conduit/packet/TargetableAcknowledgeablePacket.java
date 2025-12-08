package fr.traqueur.conduit.packet;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.Conduit;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for packets that can be sent to a specific target AND require acknowledgment (unicast with ACK).
 * 
 * <p>Example:</p>
 * <pre>
 * public record DataSyncPacket(String data) implements TargetableAcknowledgeablePacket {}
 * 
 * // Send to specific server and wait for acknowledgment
 * new DataSyncPacket("important-data")
 *     .sendWithAck("server-2")
 *     .thenAccept(ack -> {
 *         if (ack.success()) {
 *             System.out.println("Data synced to server-2!");
 *         }
 *     });
 * </pre>
 *
 * @author Traqueur
 */
public interface TargetableAcknowledgeablePacket extends TargetablePacket, AcknowledgeablePacket {

    /**
     * Regular send is not supported for acknowledgeable packets.
     * Use {@link #sendWithAck(String)} instead.
     *
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    default void send() {
        throw new UnsupportedOperationException(
                "Use sendWithAck(targetId) for acknowledgeable packets. Regular send() is not supported."
        );
    }

    /**
     * Regular sendTo is not supported for acknowledgeable packets.
     * Use {@link #sendWithAck(String)} instead.
     *
     * @throws UnsupportedOperationException always thrown
     */
    @Override
    default void sendTo(String targetId) {
        throw new UnsupportedOperationException(
            "Use sendWithAck(targetId) for acknowledgeable packets. Regular sendTo() is not supported."
        );
    }
    
    /**
     * Sends this packet to a specific target and waits for an acknowledgment response.
     * Default timeout is 5000ms (5 seconds).
     * 
     * @param targetId the unique identifier of the target instance
     * @return a CompletableFuture containing the acknowledgment response
     */
    default CompletableFuture<AckResponse> sendWithAck(String targetId) {
        return sendWithAck(targetId, 5000L);
    }
    
    /**
     * Sends this packet to a specific target and waits for an acknowledgment response with custom timeout.
     * 
     * @param targetId the unique identifier of the target instance
     * @param timeoutMs the timeout in milliseconds
     * @return a CompletableFuture containing the acknowledgment response
     */
    default CompletableFuture<AckResponse> sendWithAck(String targetId, long timeoutMs) {
        return Conduit.getInstance().sendWithAck(this, targetId, timeoutMs);
    }
}