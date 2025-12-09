package fr.traqueur.conduit.registry;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.handler.PacketHandler;
import fr.traqueur.conduit.packet.Packet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for packet handlers.
 * Maps packet types to their handlers with type-safe wrappers.
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
     * Dispatches a packet to its handler if one exists.
     *
     * @param packet the packet to dispatch
     * @param ackCallback optional callback for acknowledgment
     * @return the result of handling, or CANT_HANDLE if no handler exists
     */
    public HandlerResult dispatch(Packet packet, Consumer<AckResponse> ackCallback) {
        HandlerWrapper<?> wrapper = handlers.get(packet.getClass());

        if (wrapper == null) {
            return HandlerResult.CANT_HANDLE;
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
     */
    private record HandlerWrapper<T extends Packet>(PacketHandler<T> handler, Class<T> packetClass) {

        public HandlerResult handle(Packet packet, Consumer<AckResponse> ackCallback) {
            if(packetClass.isInstance(packet)) {
                try {
                    return handler.handle(packetClass.cast(packet), ackCallback);
                } catch (Exception e) {
                    return HandlerResult.ERROR;
                }
            }
            return HandlerResult.ERROR;
        }
    }
}