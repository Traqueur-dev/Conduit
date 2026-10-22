package fr.traqueur.conduit.registry;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.handler.PacketHandler;
import fr.traqueur.conduit.packet.Packet;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for packet handlers: at most one handler per packet type.
 *
 * @author Traqueur
 */
public class HandlerRegistry {

    private final Map<Class<? extends Packet>, HandlerWrapper<?>> handlers = new ConcurrentHashMap<>();

    /**
     * Creates a new handler registry.
     */
    public HandlerRegistry() {
    }

    /**
     * Registers a handler for a packet type, replacing any previous one.
     *
     * @param <T>         the packet type
     * @param packetClass the packet class
     * @param handler     the handler to register
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, PacketHandler<T> handler) {
        handlers.put(packetClass, new HandlerWrapper<>(handler, packetClass));
    }

    /**
     * Dispatches a packet to its handler, on the calling thread.
     *
     * @param packet      the packet to dispatch
     * @param ackCallback callback for acknowledgment, or null
     * @return a future resolving to {@code true} if a handler was found, {@code false} otherwise;
     *         completes exceptionally if the handler threw or its future failed
     */
    public CompletableFuture<Boolean> dispatch(Packet packet, Consumer<AckResponse> ackCallback) {
        HandlerWrapper<?> wrapper = handlers.get(packet.getClass());
        if (wrapper == null) {
            return CompletableFuture.completedFuture(false);
        }
        return wrapper.handle(packet, ackCallback);
    }

    /**
     * @param packetClass the packet class
     * @return true if a handler is registered
     */
    public boolean hasHandler(Class<? extends Packet> packetClass) {
        return handlers.containsKey(packetClass);
    }

    /**
     * Unregisters the handler of a packet type.
     *
     * @param packetClass the packet class
     * @return true if a handler was removed
     */
    public boolean unregisterHandler(Class<? extends Packet> packetClass) {
        return handlers.remove(packetClass) != null;
    }

    /**
     * Type-safe wrapper: the cast happens here instead of in user code. A null future returned by
     * the handler is synchronous completion.
     */
    private record HandlerWrapper<T extends Packet>(PacketHandler<T> handler, Class<T> packetClass) {

        CompletableFuture<Boolean> handle(Packet packet, Consumer<AckResponse> ackCallback) {
            CompletableFuture<Void> future;
            try {
                future = handler.handle(packetClass.cast(packet), ackCallback);
            } catch (Throwable e) {
                // Turned into a failed future, which the caller logs, counts and NACKs.
                return CompletableFuture.failedFuture(e);
            }
            return future == null ? CompletableFuture.completedFuture(true) : future.thenApply(v -> true);
        }
    }
}
