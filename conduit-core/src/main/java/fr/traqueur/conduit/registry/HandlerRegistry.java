package fr.traqueur.conduit.registry;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.handler.PacketHandler;
import fr.traqueur.conduit.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for packet handlers.
 * Maps packet types to their handlers with type-safe wrappers.
 *
 * @author Traqueur
 */
public class HandlerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerRegistry.class);

    private final Map<Class<? extends Packet>, HandlerWrapper<?>> handlers = new ConcurrentHashMap<>();

    /**
     * Creates a new handler registry.
     */
    public HandlerRegistry() {
    }

    /**
     * Registers a handler for a specific packet type.
     *
     * @param <T> the packet type
     * @param packetClass the packet class
     * @param handler the handler to register
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, PacketHandler<T> handler) {
        handlers.put(packetClass, new HandlerWrapper<>(handler, packetClass));
    }

    /**
     * Dispatches a packet to its handler.
     *
     * @param packet the packet to dispatch
     * @param ackCallback optional callback for acknowledgment
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
     * Checks if a handler is registered for a packet type.
     *
     * @param packetClass the packet class
     * @return true if a handler is registered
     */
    public boolean hasHandler(Class<? extends Packet> packetClass) {
        return handlers.containsKey(packetClass);
    }

    /**
     * Unregisters a handler for a packet type.
     *
     * @param packetClass the packet class
     */
    public void unregisterHandler(Class<? extends Packet> packetClass) {
        handlers.remove(packetClass);
    }

    /**
     * Type-safe wrapper for packet handlers.
     * Handles the casting internally to avoid unsafe casts in user code.
     * A null future returned by the handler is treated as synchronous completion.
     */
    private record HandlerWrapper<T extends Packet>(PacketHandler<T> handler, Class<T> packetClass) {

        public CompletableFuture<Boolean> handle(Packet packet, Consumer<AckResponse> ackCallback) {
            if (packetClass.isInstance(packet)) {
                try {
                    CompletableFuture<Void> future = handler.handle(packetClass.cast(packet), ackCallback);
                    if (future == null) {
                        return CompletableFuture.completedFuture(true);
                    }
                    return future
                            .thenApply(v -> true)
                            .exceptionally(e -> {
                                LOGGER.warn("Handler threw exception for packet {}", packetClass.getSimpleName(), e);
                                throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
                            });
                } catch (Exception e) {
                    LOGGER.warn("Handler threw exception for packet {}", packetClass.getSimpleName(), e);
                    return CompletableFuture.failedFuture(e);
                }
            }
            return CompletableFuture.completedFuture(false);
        }
    }
}