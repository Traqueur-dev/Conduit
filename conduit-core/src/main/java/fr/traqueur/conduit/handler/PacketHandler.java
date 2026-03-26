package fr.traqueur.conduit.handler;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.packet.Packet;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handler for processing received packets.
 *
 * <p>The handler may return a {@link CompletableFuture} to perform work asynchronously.
 * Returning {@code null} is treated as synchronous completion (equivalent to
 * {@link CompletableFuture#completedFuture(Object) CompletableFuture.completedFuture(null)}).</p>
 *
 * <p>Sync example:</p>
 * <pre>
 * conduit.registerHandler(MyPacket.class, (packet, ack) -> {
 *     process(packet);
 *     if (ack != null) ack.accept(AckResponse.success(...));
 *     return null;
 * });
 * </pre>
 *
 * <p>Async example (supply your own executor):</p>
 * <pre>
 * conduit.registerHandler(MyPacket.class, (packet, ack) ->
 *     CompletableFuture.runAsync(() -> {
 *         process(packet);
 *         if (ack != null) ack.accept(AckResponse.success(...));
 *     }, executor)
 * );
 * </pre>
 *
 * @param <T> the type of packet this handler processes
 * @author Traqueur
 */
@FunctionalInterface
public interface PacketHandler<T extends Packet> {

    /**
     * Handles a received packet.
     *
     * @param packet the received packet
     * @param ackCallback callback to send acknowledgment (null if packet doesn't require ACK)
     * @return a future that completes when handling is done, or {@code null} for synchronous completion
     */
    CompletableFuture<Void> handle(T packet, Consumer<AckResponse> ackCallback);
}