package fr.traqueur.conduit.registry;

import fr.traqueur.conduit.packet.Packet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-way mapping between wire names and packet classes, with the channel of each type.
 *
 * <p>A wire name maps to exactly one class and a class to exactly one wire name; anything else
 * is refused. Reads are lock-free; writes are serialized.</p>
 *
 * @author Traqueur
 */
public class PacketRegistry {

    private final Map<String, Registration> byWire = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet>, Registration> byClass = new ConcurrentHashMap<>();

    /**
     * Creates an empty registry.
     */
    public PacketRegistry() {
    }

    /**
     * Registers a type.
     *
     * @param wireName    the wire name
     * @param packetClass the class
     * @param channel     the channel the type travels on
     * @return {@code true} if added, {@code false} if this exact registration already existed
     * @throws IllegalStateException if the wire name belongs to another class, or the class to
     *                               another wire name or channel
     */
    public synchronized boolean register(String wireName, Class<? extends Packet> packetClass, String channel) {
        Registration existingByClass = byClass.get(packetClass);
        if (existingByClass != null) {
            if (existingByClass.wireName().equals(wireName) && existingByClass.channel().equals(channel)) {
                return false;
            }
            throw new IllegalStateException("Packet class " + packetClass.getName()
                    + " is already registered as '" + existingByClass.wireName() + "' on channel '"
                    + existingByClass.channel() + "'; cannot register it as '" + wireName
                    + "' on channel '" + channel + "'");
        }
        Registration existingByWire = byWire.get(wireName);
        if (existingByWire != null) {
            throw new IllegalStateException("Wire name '" + wireName + "' is already used by "
                    + existingByWire.packetClass().getName() + "; cannot register it for "
                    + packetClass.getName());
        }
        Registration registration = new Registration(wireName, packetClass, channel);
        byWire.put(wireName, registration);
        byClass.put(packetClass, registration);
        return true;
    }

    /**
     * Removes a type.
     *
     * @param packetClass the class
     * @return the removed registration, or null if it was not registered
     */
    public synchronized Registration unregister(Class<? extends Packet> packetClass) {
        Registration registration = byClass.remove(packetClass);
        if (registration != null) {
            byWire.remove(registration.wireName());
        }
        return registration;
    }

    /**
     * @param wireName the wire name
     * @return the registration, or null
     */
    public Registration byWire(String wireName) {
        return byWire.get(wireName);
    }

    /**
     * @param packetClass the class
     * @return the registration, or null
     */
    public Registration byClass(Class<? extends Packet> packetClass) {
        return byClass.get(packetClass);
    }

    /**
     * @param channel a channel
     * @return whether at least one registered type uses it
     */
    public boolean isChannelUsed(String channel) {
        return byClass.values().stream().anyMatch(r -> r.channel().equals(channel));
    }

    /**
     * A registered type.
     *
     * @param wireName    the wire name
     * @param packetClass the class
     * @param channel     the channel
     */
    public record Registration(String wireName, Class<? extends Packet> packetClass, String channel) {}
}
