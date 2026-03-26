package fr.traqueur.conduit.registry;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.handler.AsyncPacketHandler;
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
 * Supports both synchronous ({@link PacketHandler}) and asynchronous ({@link AsyncPacketHandler}) handlers.
 *
 * @author Traqueur
 */
public class HandlerRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(HandlerRegistry.class);

    private final Map<Class<? extends Packet>, SyncWrapper<?>> handlers = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet>, AsyncWrapper<?>> asyncHandlers = new ConcurrentHashMap<>();

    /**
     * Creates a new handler registry.
     */
    public HandlerRegistry() {
    }

    /**
     * Registers a synchronous handler for a specific packet type.
     *
     * @param <T> the packet type
     * @param packetClass the packet class
     * @param handler the handler to register
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, PacketHandler<T> handler) {
        handlers.put(packetClass, new SyncWrapper<>(handler, packetClass));
    }

    /**
     * Registers an asynchronous handler for a specific packet type.
     *
     * @param <T> the packet type
     * @param packetClass the packet class
     * @param handler the async handler to register
     */
    public <T extends Packet> void registerAsyncHandler(Class<T> packetClass, AsyncPacketHandler<T> handler) {
        asyncHandlers.put(packetClass, new AsyncWrapper<>(handler, packetClass));
    }

    /**
     * Dispatches a packet synchronously to its handler if one exists.
     *
     * @param packet the packet to dispatch
     * @param ackCallback optional callback for acknowledgment
     * @return true if a handler was found and invoked, false if no handler is registered
     */
    public boolean dispatch(Packet packet, Consumer<AckResponse> ackCallback) {
        SyncWrapper<?> wrapper = handlers.get(packet.getClass());

        if (wrapper == null) {
            return false;
        }

        return wrapper.handle(packet, ackCallback);
    }

    /**
     * Dispatches a packet to its handler asynchronously.
     *
     * <ul>
     *   <li>If an async handler is registered: calls it and returns its future mapped to {@code Boolean}.</li>
     *   <li>If only a sync handler is registered: calls it synchronously and returns a completed future of {@code true}.</li>
     *   <li>If no handler is registered: returns a completed future of {@code false}.</li>
     * </ul>
     *
     * @param packet the packet to dispatch
     * @param ackCallback optional callback for acknowledgment
     * @return a CompletableFuture that resolves to true if a handler was found, false otherwise
     */
    public CompletableFuture<Boolean> dispatchAsync(Packet packet, Consumer<AckResponse> ackCallback) {
        AsyncWrapper<?> asyncWrapper = asyncHandlers.get(packet.getClass());
        if (asyncWrapper != null) {
            return asyncWrapper.handle(packet, ackCallback)
                    .thenApply(v -> true);
        }

        SyncWrapper<?> syncWrapper = handlers.get(packet.getClass());
        if (syncWrapper != null) {
            syncWrapper.handle(packet, ackCallback);
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.completedFuture(false);
    }

    /**
     * Checks if a handler (sync or async) is registered for a packet type.
     *
     * @param packetClass the packet class
     * @return true if a handler is registered
     */
    public boolean hasHandler(Class<? extends Packet> packetClass) {
        return handlers.containsKey(packetClass) || asyncHandlers.containsKey(packetClass);
    }

    /**
     * Unregisters a handler for a packet type (both sync and async).
     *
     * @param packetClass the packet class
     */
    public void unregisterHandler(Class<? extends Packet> packetClass) {
        handlers.remove(packetClass);
        asyncHandlers.remove(packetClass);
    }

    /**
     * Type-safe wrapper for synchronous packet handlers.
     */
    private record SyncWrapper<T extends Packet>(PacketHandler<T> handler, Class<T> packetClass) {

        public boolean handle(Packet packet, Consumer<AckResponse> ackCallback) {
            if (packetClass.isInstance(packet)) {
                try {
                    handler.handle(packetClass.cast(packet), ackCallback);
                    return true;
                } catch (Exception e) {
                    LOGGER.warn("Handler threw exception for packet {}", packetClass.getSimpleName(), e);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Type-safe wrapper for asynchronous packet handlers.
     */
    private record AsyncWrapper<T extends Packet>(AsyncPacketHandler<T> handler, Class<T> packetClass) {

        public CompletableFuture<Void> handle(Packet packet, Consumer<AckResponse> ackCallback) {
            if (packetClass.isInstance(packet)) {
                try {
                    return handler.handle(packetClass.cast(packet), ackCallback);
                } catch (Exception e) {
                    LOGGER.warn("Async handler threw exception for packet {}", packetClass.getSimpleName(), e);
                    return CompletableFuture.failedFuture(e);
                }
            }
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Packet type mismatch: expected " + packetClass.getSimpleName()));
        }
    }
}
