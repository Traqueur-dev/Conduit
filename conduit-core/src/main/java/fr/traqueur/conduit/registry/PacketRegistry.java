package fr.traqueur.conduit.registry;

import fr.traqueur.conduit.packet.Packet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for packet types, mapping packet class names to their classes.
 * Handles packet serialization/deserialization type mapping.
 *
 * @author Traqueur
 */
public class PacketRegistry {

    private final Map<String, Class<? extends Packet>> packetTypes = new ConcurrentHashMap<>();
    private final Map<Class<? extends Packet>, String> reverseMapping = new ConcurrentHashMap<>();

    /**
     * Creates a new packet registry.
     */
    public PacketRegistry() {
    }
    
    /**
     * Registers a packet type with its simple class name as identifier.
     * 
     * @param packetClass the packet class to register
     */
    public void register(Class<? extends Packet> packetClass) {
        String typeName = packetClass.getSimpleName();
        register(typeName, packetClass);
    }
    
    /**
     * Registers a packet type with a custom type name.
     * 
     * @param typeName custom type identifier
     * @param packetClass the packet class
     */
    public void register(String typeName, Class<? extends Packet> packetClass) {
        packetTypes.put(typeName, packetClass);
        reverseMapping.put(packetClass, typeName);
    }
    
    /**
     * Gets the packet class for a given type name.
     * 
     * @param typeName the type identifier
     * @return the packet class, or null if not found
     */
    public Class<? extends Packet> getPacketClass(String typeName) {
        return packetTypes.get(typeName);
    }
    
    /**
     * Gets the type name for a given packet class.
     * 
     * @param packetClass the packet class
     * @return the type identifier, or the simple class name if not registered
     */
    public String getTypeName(Class<? extends Packet> packetClass) {
        return reverseMapping.getOrDefault(packetClass, packetClass.getSimpleName());
    }
    
    /**
     * Gets the type name for a packet instance.
     * 
     * @param packet the packet instance
     * @return the type identifier
     */
    public String getTypeName(Packet packet) {
        return getTypeName(packet.getClass());
    }
    
    /**
     * Checks if a packet type is registered.
     * 
     * @param typeName the type identifier
     * @return true if registered
     */
    public boolean isRegistered(String typeName) {
        return packetTypes.containsKey(typeName);
    }
    
    /**
     * Checks if a packet class is registered.
     * 
     * @param packetClass the packet class
     * @return true if registered
     */
    public boolean isRegistered(Class<? extends Packet> packetClass) {
        return reverseMapping.containsKey(packetClass);
    }
    
    /**
     * Checks if a packet class is NOT registered.
     * 
     * @param packetClass the packet class
     * @return true if not registered
     */
    public boolean isNotRegistered(Class<? extends Packet> packetClass) {
        return !isRegistered(packetClass);
    }
}