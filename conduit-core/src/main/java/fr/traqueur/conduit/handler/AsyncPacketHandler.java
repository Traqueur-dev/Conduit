package fr.traqueur.conduit.handler;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.packet.Packet;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Asynchronous handler for incoming packets.
 * Returns a {@link CompletableFuture} to allow non-blocking packet processing.
 *
 * <p><strong>Executor note:</strong> Conduit does not provide a default executor.
 * Supply your own via {@link CompletableFuture#runAsync(Runnable, java.util.concurrent.Executor)}
 * to control the thread pool used for handler execution. If you use
 * {@link CompletableFuture#runAsync(Runnable)} without an executor, the common
 * fork-join pool will be used, which may be unsuitable for blocking operations.</p>
 *
 * <p>Example:</p>
 * <pre>
 * ExecutorService executor = Executors.newFixedThreadPool(4);
 * conduit.registerAsyncHandler(MyPacket.class, (packet, ackCallback) ->
 *     CompletableFuture.runAsync(() -> {
 *         // process packet...
 *         if (ackCallback != null) ackCallback.accept(AckResponse.success(...));
 *     }, executor)
 * );
 * </pre>
 *
 * @param <T> the type of packet this handler processes
 * @author Traqueur
 */
@FunctionalInterface
public interface AsyncPacketHandler<T extends Packet> {

    /**
     * Handles a received packet asynchronously.
     *
     * @param packet the received packet
     * @param ackCallback callback to send acknowledgment (null if packet doesn't require ACK)
     * @return a CompletableFuture that completes when handling is done
     */
    CompletableFuture<Void> handle(T packet, Consumer<AckResponse> ackCallback);
}
