package fr.traqueur.conduit.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Binary protocol envelope for packet transmission.
 * 
 * <p>Format:</p>
 * <pre>
 * [1 byte: flags] [4 bytes: packetType length] [N bytes: packetType]
 * [4 bytes: ackId length] [N bytes: ackId]
 * [4 bytes: ackResponse length] [N bytes: ackResponse JSON]
 * [4 bytes: payload length] [N bytes: payload]
 * </pre>
 * 
 * Flags (8 bits):
 * - Bit 0: requiresAck
 * - Bit 1: isAckResponse
 * - Bits 2-7: reserved
 *
 * @author Traqueur
 */
public class PacketEnvelope {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
                @Override
                public void write(JsonWriter out, Instant value) throws IOException {
                    out.value(value.toString());
                }

                @Override
                public Instant read(JsonReader in) throws IOException {
                    return Instant.parse(in.nextString());
                }
            })
            .create();

    private final String packetType;
    private final byte[] payload;
    private final boolean requiresAck;
    private final boolean isAckResponse;
    private final String ackId;
    private final AckResponse ackResponse;
    private final Map<String, String> metadata;

    /**
     * Creates a new packet envelope.
     *
     * @param packetType the packet type identifier
     * @param payload the serialized and compressed packet data
     * @param requiresAck whether this packet requires acknowledgment
     * @param isAckResponse whether this envelope contains an ACK response
     * @param ackId the acknowledgment ID (for ACK tracking)
     * @param ackResponse the ACK response (if this is an ACK response envelope)
     * @param metadata transport-specific routing metadata
     */
    public PacketEnvelope(String packetType, byte[] payload, boolean requiresAck,
                          boolean isAckResponse, String ackId, AckResponse ackResponse,  Map<String, String> metadata) {
        this.packetType = packetType;
        this.payload = payload;
        this.requiresAck = requiresAck;
        this.isAckResponse = isAckResponse;
        this.ackId = ackId;
        this.ackResponse = ackResponse;
        this.metadata = metadata;
    }

    /**
     * Gets the packet type identifier.
     *
     * @return the packet type
     */
    public String packetType() {
        return packetType;
    }

    /**
     * Gets the serialized and compressed packet payload.
     *
     * @return the payload bytes
     */
    public byte[] payload() {
        return payload;
    }

    /**
     * Checks if this packet requires acknowledgment.
     *
     * @return true if ACK is required
     */
    public boolean requiresAck() {
        return requiresAck;
    }

    /**
     * Checks if this envelope contains an ACK response.
     *
     * @return true if this is an ACK response
     */
    public boolean isAckResponse() {
        return isAckResponse;
    }

    /**
     * Gets the acknowledgment ID for ACK tracking.
     *
     * @return the ACK ID, or null if not applicable
     */
    public String ackId() {
        return ackId;
    }

    /**
     * Gets the ACK response if this envelope contains one.
     *
     * @return the ACK response, or null if not applicable
     */
    public AckResponse ackResponse() {
        return ackResponse;
    }

    /**
     * Gets the metadata map for transport-specific routing information.
     * <p><strong>Note:</strong> This map is intentionally mutable. Transport implementations
     * inject routing metadata (e.g., replyTo, correlationId) after deserialization.</p>
     *
     * @return the mutable metadata map
     */
    public Map<String, String> metadata() { return metadata; }

    /**
     * Serializes this envelope to bytes using the binary protocol format.
     *
     * @return the serialized envelope
     * @throws IOException if serialization fails
     */
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Write flags
        byte flags = 0;
        if (requiresAck) flags |= 0x01;
        if (isAckResponse) flags |= 0x02;
        dos.writeByte(flags);

        // Write packetType
        writeString(dos, packetType);

        // Write ackId
        writeString(dos, ackId);

        // Write ackResponse (as JSON)
        if (ackResponse != null) {
            String ackJson = serializeAckResponse(ackResponse);
            writeString(dos, ackJson);
        } else {
            dos.writeInt(0);
        }

        // Write metadata
        dos.writeInt(metadata.size());
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            writeString(dos, entry.getKey());
            writeString(dos, entry.getValue());
        }

        // Write payload
        if (payload != null) {
            dos.writeInt(payload.length);
            dos.write(payload);
        } else {
            dos.writeInt(0);
        }

        dos.flush();
        return baos.toByteArray();
    }

    /**
     * Deserializes an envelope from bytes.
     *
     * @param data the serialized envelope data
     * @return the deserialized PacketEnvelope
     * @throws IOException if deserialization fails
     */
    public static PacketEnvelope fromBytes(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);

        // Read flags
        byte flags = dis.readByte();
        boolean requiresAck = (flags & 0x01) != 0;
        boolean isAckResponse = (flags & 0x02) != 0;

        // Read packetType
        String packetType = readString(dis);

        // Read ackId
        String ackId = readString(dis);

        // Read ackResponse
        String ackJson = readString(dis);
        AckResponse ackResponse = null;
        if (ackJson != null && !ackJson.isEmpty()) {
            ackResponse = deserializeAckResponse(ackJson);
        }

        // Read metadata
        int metadataSize = dis.readInt();
        Map<String, String> metadata = new HashMap<>();
        for (int i = 0; i < metadataSize; i++) {
            String key = readString(dis);
            String value = readString(dis);
            if (key != null && value != null) {
                metadata.put(key, value);
            }
        }

        // Read payload
        int payloadLength = dis.readInt();
        byte[] payload = null;
        if (payloadLength > 0) {
            payload = new byte[payloadLength];
            dis.readFully(payload);
        }

        return new PacketEnvelope(packetType, payload, requiresAck, isAckResponse, ackId, ackResponse, metadata);
    }
    
    private static void writeString(DataOutputStream dos, String str) throws IOException {
        if (str == null || str.isEmpty()) {
            dos.writeInt(0);
        } else {
            byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }
    }
    
    private static String readString(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length == 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    
    private static String serializeAckResponse(AckResponse ackResponse) {
        return GSON.toJson(ackResponse);
    }

    private static AckResponse deserializeAckResponse(String json) {
        return GSON.fromJson(json, AckResponse.class);
    }
}