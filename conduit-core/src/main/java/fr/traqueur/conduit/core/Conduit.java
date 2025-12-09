package fr.traqueur.conduit.core;

import com.google.gson.Gson;
import fr.traqueur.conduit.compression.Compressor;
import fr.traqueur.conduit.compression.NoOpCompressor;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.handler.PacketHandler;
import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.packet.TargetableAcknowledgeablePacket;
import fr.traqueur.conduit.packet.TargetablePacket;
import fr.traqueur.conduit.registry.HandlerRegistry;
import fr.traqueur.conduit.registry.PacketRegistry;
import fr.traqueur.conduit.serialization.JsonSerializer;
import fr.traqueur.conduit.serialization.Serializer;
import fr.traqueur.conduit.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the Conduit messaging system.
 * Manages packet sending, receiving, and acknowledgments.
 *
 * @author Traqueur
 */
public class Conduit {

    private static final Logger LOGGER = LoggerFactory.getLogger(Conduit.class);
    private static Conduit instance;

    private final Transport transport;
    private final Serializer serializer;
    private final Compressor compressor;
    private final PacketRegistry packetRegistry;
    private final HandlerRegistry handlerRegistry;
    private final String defaultChannel;
    private final String instanceId;

    private final Set<String> channelsToSubscribe = ConcurrentHashMap.newKeySet();

    private Conduit(Transport transport,
                    Serializer serializer,
                    Compressor compressor,
                    String defaultChannel,
                    String instanceId) {
        this.transport = transport;
        this.serializer = serializer;
        this.compressor = compressor;
        this.packetRegistry = new PacketRegistry();
        this.handlerRegistry = new HandlerRegistry();
        this.defaultChannel = defaultChannel;
        this.instanceId = instanceId;

        LOGGER.info("Conduit initialized with transport: {}, serializer: {}, compressor: {}",
                transport.getType(), serializer.getType(), compressor.getType());
    }

    /**
     * Gets the singleton instance.
     *
     * @return the Conduit instance
     * @throws IllegalStateException if Conduit is not initialized
     */
    public static Conduit getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Conduit is not initialized. Call Conduit.builder().build() first.");
        }
        return instance;
    }

    // ===== Registration Methods =====

    /**
     * Registers a packet type.
     *
     * @param packetClass the packet class to register
     */
    /**
     * Registers a packet type.
     *
     * @param packetClass the packet class to register
     */
    public void registerPacket(Class<? extends Packet> packetClass) {
        packetRegistry.register(packetClass);

        // Remember to subscribe to custom channel
        PacketMeta meta = packetClass.getAnnotation(PacketMeta.class);
        if (meta != null) {
            channelsToSubscribe.add(meta.channel());
        }

        LOGGER.debug("Registered packet: {}", packetClass.getSimpleName());
    }

    /**
     * Registers a packet handler.
     *
     * @param <T>         the packet type
     * @param packetClass the packet class
     * @param handler     the handler to register
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, PacketHandler<T> handler) {
        handlerRegistry.registerHandler(packetClass, handler);
        LOGGER.debug("Registered handler for: {}", packetClass.getSimpleName());
    }
    // ===== Send Methods (called by Packet interfaces) =====

    /**
     * Sends a packet via broadcast to all listening instances.
     * Internal method called by {@link Packet#send()}.
     *
     * @param packet the packet to send
     */
    public void send(Packet packet) {
        String channel = getChannelForPacket(packet);

        if (packetRegistry.isNotRegistered(packet.getClass())) {
            LOGGER.error("Packet {} is not registered", packet.getClass().getSimpleName());
            return;
        }

        try {
            byte[] data = wrapPacket(packet, false, null);
            transport.broadcast(channel, data);
            LOGGER.debug("Sent packet {} to channel {}", packet.getClass().getSimpleName(), channel);

        } catch (Exception e) {
            LOGGER.error("Failed to send packet {}", packet.getClass().getSimpleName(), e);
        }
    }

    /**
     * Sends a packet to a specific target instance (unicast).
     * Internal method called by {@link fr.traqueur.conduit.packet.TargetablePacket#sendTo(String)}.
     *
     * @param packet the packet to send
     * @param targetId the unique identifier of the target instance
     */
    public void sendTo(Packet packet, String targetId) {
        String channel = getChannelForPacket(packet);

        if (packetRegistry.isNotRegistered(packet.getClass())) {
            LOGGER.error("Packet {} is not registered", packet.getClass().getSimpleName());
            return;
        }

        try {
            byte[] data = wrapPacket(packet, false, null);
            transport.sendTo(channel, targetId, data);
            LOGGER.debug("Sent packet {} to target {} on channel {}",
                    packet.getClass().getSimpleName(), targetId, channel);

        } catch (Exception e) {
            LOGGER.error("Failed to send packet {} to target {}",
                    packet.getClass().getSimpleName(), targetId, e);
        }
    }

    /**
     * Sends a packet via broadcast and waits for an acknowledgment response.
     * Internal method called by {@link fr.traqueur.conduit.packet.AcknowledgeablePacket#sendWithAck(long)}.
     *
     * @param packet the packet to send
     * @param timeoutMs timeout in milliseconds
     * @return a CompletableFuture containing the acknowledgment response
     */
    public CompletableFuture<AckResponse> sendWithAck(Packet packet, long timeoutMs) {
        String channel = getChannelForPacket(packet);

        if (packetRegistry.isNotRegistered(packet.getClass())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Packet " + packet.getClass().getSimpleName() + " is not registered")
            );
        }

        try {
            String ackId = UUID.randomUUID().toString();
            byte[] data = wrapPacket(packet, true, ackId);

            // Delegate ACK handling to transport
            CompletableFuture<AckResponse> future = transport.broadcastWithAck(channel, data, timeoutMs);

            LOGGER.debug("Sent packet {} with ACK on channel {}", packet.getClass().getSimpleName(), channel);
            return future;

        } catch (Exception e) {
            LOGGER.error("Failed to send packet {} with ACK", packet.getClass().getSimpleName(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Sends a packet to a specific target and waits for an acknowledgment response.
     * Internal method called by {@link fr.traqueur.conduit.packet.TargetableAcknowledgeablePacket#sendWithAck(String, long)}.
     *
     * @param packet the packet to send
     * @param targetId the unique identifier of the target instance
     * @param timeoutMs timeout in milliseconds
     * @return a CompletableFuture containing the acknowledgment response
     */
    public CompletableFuture<AckResponse> sendWithAck(Packet packet, String targetId, long timeoutMs) {
        String channel = getChannelForPacket(packet);

        if (packetRegistry.isNotRegistered(packet.getClass())) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Packet " + packet.getClass().getSimpleName() + " is not registered")
            );
        }

        try {
            String ackId = UUID.randomUUID().toString();
            byte[] data = wrapPacket(packet, true, ackId);

            // Delegate ACK handling to transport
            CompletableFuture<AckResponse> future = transport.sendToWithAck(channel, targetId, data, timeoutMs);

            LOGGER.debug("Sent packet {} with ACK to target {} on channel {}",
                    packet.getClass().getSimpleName(), targetId, channel);
            return future;

        } catch (Exception e) {
            LOGGER.error("Failed to send packet {} with ACK to target {}",
                    packet.getClass().getSimpleName(), targetId, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ===== Packet Reception Methods =====

    /**
     * Initializes the transport and subscribes to channels.
     * Should be called after registering all packets and handlers.
     *
     * @throws Exception if transport connection or channel subscription fails
     */
    public void start() throws Exception {
        transport.connect();

        // Subscribe to default channel
        transport.subscribe(defaultChannel, (ch, data) -> handleIncomingPacket(ch, data, true)); // true = broadcast
        transport.subscribeUnicast(defaultChannel, instanceId, (ch, data) -> handleIncomingPacket(ch, data, false)); // false = unicast

        // Subscribe to all custom channels from @PacketMeta
        for (String channel : channelsToSubscribe) {
            if (!channel.equals(defaultChannel)) {
                transport.subscribe(channel, (ch, data) -> handleIncomingPacket(ch, data, true)); // true = broadcast
                transport.subscribeUnicast(channel, instanceId, (ch, data) -> handleIncomingPacket(ch, data, false)); // false = unicast
                LOGGER.info("Subscribed to custom channel: {}", channel);
            }
        }

        LOGGER.info("Conduit started and listening on {} channels with instanceId: {}",
                channelsToSubscribe.size() + 1, instanceId);
    }

    // ===== Shutdown =====

    /**
     * Shuts down Conduit and closes all resources.
     */
    public void shutdown() {
        try {
            LOGGER.info("Shutting down Conduit...");

            transport.close();

            LOGGER.info("Conduit shutdown complete");
        } catch (Exception e) {
            LOGGER.error("Error during shutdown", e);
        }
    }

    private byte[] wrapPacket(Packet packet, boolean requiresAck, String ackId) throws Exception {
        String packetType = packetRegistry.getTypeName(packet);
        byte[] payload = serializeAndCompress(packet);

        PacketEnvelope envelope = new PacketEnvelope(
                packetType,
                payload,
                requiresAck,
                false, // isAckResponse
                ackId,
                null,   // ackResponse
                new HashMap<>()
        );

        // Add our instanceId to metadata to identify the sender
        envelope.metadata().put("senderId", instanceId);

        return envelope.toBytes();
    }

    private byte[] wrapAckResponse(String ackId, AckResponse ackResponse) throws Exception {
        PacketEnvelope envelope = new PacketEnvelope(
                null,           // packetType not needed for ACK response
                new byte[0],    // payload not needed for ACK response
                false,          // requiresAck
                true,           // isAckResponse
                ackId,
                ackResponse,
                new HashMap<>()
        );

        return envelope.toBytes();
    }

    /**
     * Handles incoming packet data.
     *
     * @param channel the channel the packet was received on
     * @param data the raw packet data
     */
    private void handleIncomingPacket(String channel, byte[] data, boolean isBroadcast) {
        try {
            PacketEnvelope envelope = PacketEnvelope.fromBytes(data);

            // Check if this is an ACK response - le transport le gère maintenant
            if (envelope.isAckResponse()) {
                LOGGER.trace("Ignoring ACK response - handled by transport");
                return;
            }

            // IMPORTANT: For broadcast, ignore packets we sent ourselves
            if (isBroadcast) {
                String senderId = envelope.metadata().get("senderId");
                if (senderId != null && senderId.equals(instanceId)) {
                    LOGGER.trace("Ignoring broadcast packet from self: {}", envelope.packetType());
                    return;
                }
            }

            // Get packet class
            Class<? extends Packet> packetClass = packetRegistry.getPacketClass(envelope.packetType());
            if (packetClass == null) {
                LOGGER.error("Unknown packet type: {}", envelope.packetType());
                return;
            }

            // Deserialize packet
            Packet packet = decompressAndDeserialize(envelope.payload(), packetClass);

            // Dispatch to handler
            if (envelope.requiresAck()) {
                // Capture metadata for ACK response
                Map<String, String> metadata = envelope.metadata();

                HandlerResult result = handlerRegistry.dispatch(packet, ackResponse -> {
                    sendAckResponse(channel, envelope.ackId(), ackResponse, metadata);
                });

                if (result == HandlerResult.CANT_HANDLE) {
                    sendAckResponse(channel, envelope.ackId(),
                            AckResponse.failure(envelope.ackId(), "No handler registered"), metadata);
                }
            } else {
                handlerRegistry.dispatch(packet, null);
            }

            LOGGER.debug("Handled packet: {}", packetClass.getSimpleName());

        } catch (Exception e) {
            LOGGER.error("Failed to handle incoming packet", e);
        }
    }

    /**
     * Sends an ACK response back to the sender.
     */
    /**
     * Sends an ACK response back to the sender.
     */
    private void sendAckResponse(String channel, String ackId, AckResponse ackResponse, Map<String, String> metadata) {
        try {
            byte[] data = wrapAckResponse(ackId, ackResponse);

            // Use transport-specific ACK sending (delegates to transport implementation)
            transport.sendAckResponse(channel, data, metadata);

            LOGGER.debug("Sent ACK response for id: {}", ackId);

        } catch (Exception e) {
            LOGGER.error("Failed to send ACK response for id: {}", ackId, e);
        }
    }

    private String getChannelForPacket(Packet packet) {
        PacketMeta meta = packet.getClass().getAnnotation(PacketMeta.class);
        return meta != null ? meta.channel() : defaultChannel;
    }

    private byte[] serializeAndCompress(Packet packet) throws Exception {
        byte[] serialized = serializer.serialize(packet);
        return compressor.compress(serialized);
    }

    private Packet decompressAndDeserialize(byte[] data, Class<? extends Packet> packetClass) throws Exception {
        byte[] decompressed = compressor.decompress(data);
        return serializer.deserialize(decompressed, packetClass);
    }

    /**
     * Creates a new builder for configuring Conduit.
     *
     * @return a new builder instance
     */
    public static ConduitBuilder builder() {
        return new ConduitBuilder();
    }

    // ===== Builder =====

    /**
     * Builder for configuring and initializing Conduit.
     */
    public static class ConduitBuilder {

        private Transport transport;
        private Serializer serializer = new JsonSerializer(); // Default
        private Compressor compressor = new NoOpCompressor(); // Default
        private String defaultChannel = "conduit:packets"; // Default
        private String instanceId = UUID.randomUUID().toString(); // Default random

        private ConduitBuilder() {
        }

        /**
         * Sets the transport to use (Redis, RabbitMQ, etc.).
         * REQUIRED.
         *
         * @param transport the transport implementation
         * @return this builder
         */
        public ConduitBuilder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * Sets the serializer to use.
         * Default: JsonSerializer
         *
         * @param serializer the serializer implementation
         * @return this builder
         */
        public ConduitBuilder serializer(Serializer serializer) {
            this.serializer = serializer;
            return this;
        }

        /**
         * Sets the compressor to use.
         * Default: NoOpCompressor (no compression)
         *
         * @param compressor the compressor implementation
         * @return this builder
         */
        public ConduitBuilder compressor(Compressor compressor) {
            this.compressor = compressor;
            return this;
        }

        /**
         * Sets the default channel for packets without @PacketMeta annotation.
         * Default: "conduit:packets"
         *
         * @param defaultChannel the default channel name
         * @return this builder
         */
        public ConduitBuilder defaultChannel(String defaultChannel) {
            this.defaultChannel = defaultChannel;
            return this;
        }

        /**
         * Sets the unique identifier for this instance (used for unicast).
         * Default: random UUID
         *
         * @param instanceId the instance identifier
         * @return this builder
         */
        public ConduitBuilder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        /**
         * Builds and initializes the Conduit instance.
         *
         * @return the initialized Conduit instance
         * @throws IllegalStateException if transport is not set
         */
        public Conduit build() {
            if (transport == null) {
                throw new IllegalStateException("Transport must be set");
            }

            if (Conduit.instance != null) {
                LOGGER.warn("Conduit instance already exists, replacing it");
            }

            Conduit.instance = new Conduit(
                    transport,
                    serializer,
                    compressor,
                    defaultChannel,
                    instanceId
            );

            return Conduit.instance;
        }
    }
}