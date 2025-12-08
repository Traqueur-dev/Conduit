package fr.traqueur.conduit.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import fr.traqueur.conduit.packet.Packet;

import java.nio.charset.StandardCharsets;

/**
 * JSON serializer implementation using Gson.
 * Supports custom type adapters for complex types.
 *
 * @author Traqueur
 */
public class JsonSerializer implements Serializer {
    
    private final GsonBuilder builder;
    private volatile Gson gson;
    
    public JsonSerializer() {
        this.builder = new GsonBuilder()
                .disableHtmlEscaping();
        this.gson = builder.create();
    }
    
    /**
     * Registers a custom type adapter for a specific class.
     * Rebuilds the Gson instance after registration.
     *
     * @param <T> the type to register the adapter for
     * @param type the class to register the adapter for
     * @param typeAdapter the type adapter to register
     */
    public <T> void registerTypeAdapter(Class<T> type, TypeAdapter<T> typeAdapter) {
        this.builder.registerTypeAdapter(type, typeAdapter);
        this.gson = builder.create();
    }
    
    @Override
    public byte[] serialize(Packet packet) throws Exception {
        String json = gson.toJson(packet, packet.getClass());
        return json.getBytes(StandardCharsets.UTF_8);
    }
    
    @Override
    public <T extends Packet> T deserialize(byte[] data, Class<T> packetClass) throws Exception {
        String json = new String(data, StandardCharsets.UTF_8);
        return gson.fromJson(json, packetClass);
    }
    
    @Override
    public String getType() {
        return "json";
    }
}