package fr.traqueur.conduit.serialization;

import fr.traqueur.conduit.packet.Packet;

/**
 * Abstraction for packet serialization strategies (JSON, Binary, etc.).
 *
 * @author Traqueur
 */
public interface Serializer {
    
    /**
     * Serializes a packet to bytes.
     * 
     * @param packet the packet to serialize
     * @return serialized data
     * @throws Exception if serialization fails
     */
    byte[] serialize(Packet packet) throws Exception;
    
    /**
     * Deserializes bytes to a packet.
     * 
     * @param data the serialized data
     * @param packetClass the target packet class
     * @param <T> the packet type
     * @return the deserialized packet
     * @throws Exception if deserialization fails
     */
    <T extends Packet> T deserialize(byte[] data, Class<T> packetClass) throws Exception;
    
    /**
     * Gets the serializer type identifier.
     * 
     * @return serializer type (e.g., "json", "binary")
     */
    String getType();
}