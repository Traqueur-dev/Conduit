package fr.traqueur.conduit.transport;

import fr.traqueur.conduit.core.AckResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Abstraction layer for different transport mechanisms (Redis, RabbitMQ, etc.).
 * Handles both broadcast and unicast message delivery with optional acknowledgment.
 *
 * @author Traqueur
 */
public interface Transport extends AutoCloseable {

    /**
     * Initializes and connects the transport.
     *
     * @throws Exception if connection fails
     */
    void connect() throws Exception;

    /**
     * Sends data via broadcast (all instances listening on the channel will receive it).
     *
     * @param channel the target channel/topic
     * @param data the serialized data to send
     */
    void broadcast(String channel, byte[] data);

    /**
     * Sends data to a specific target instance (unicast).
     *
     * @param channel the target channel/topic
     * @param targetId the unique identifier of the target instance
     * @param data the serialized data to send
     */
    void sendTo(String channel, String targetId, byte[] data);

    /**
     * Sends data via broadcast and waits for acknowledgment.
     *
     * @param channel the target channel/topic
     * @param data the serialized data to send
     * @param timeoutMs timeout in milliseconds
     * @return CompletableFuture containing the acknowledgment
     */
    CompletableFuture<AckResponse> broadcastWithAck(String channel, byte[] data, long timeoutMs);

    /**
     * Sends data to a specific target and waits for acknowledgment.
     *
     * @param channel the target channel/topic
     * @param targetId the unique identifier of the target instance
     * @param data the serialized data to send
     * @param timeoutMs timeout in milliseconds
     * @return CompletableFuture containing the acknowledgment
     */
    CompletableFuture<AckResponse> sendToWithAck(String channel, String targetId, byte[] data, long timeoutMs);

    /**
     * Subscribes to a channel to receive broadcast messages.
     *
     * @param channel the channel to subscribe to
     * @param handler the callback to handle received data (channel, data)
     */
    void subscribe(String channel, BiConsumer<String, byte[]> handler);

    /**
     * Subscribes to receive unicast messages targeted to this instance.
     *
     * @param channel the channel to subscribe to
     * @param instanceId the unique identifier of this instance
     * @param handler the callback to handle received data (channel, data)
     */
    void subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler);

    /**
     * Unsubscribes from a channel.
     *
     * @param channel the channel to unsubscribe from
     */
    void unsubscribe(String channel);

    /**
     * Checks if the transport is currently connected.
     *
     * @return true if connected
     */
    boolean isConnected();


    /**
     * Sends an ACK response back to the sender.
     * Uses transport-specific metadata to route the response correctly.
     *
     * @param channel the original channel
     * @param data the ACK response data
     * @param metadata transport-specific metadata (e.g., replyTo, correlationId for RabbitMQ)
     */
    default void sendAckResponse(String channel, byte[] data, Map<String, String> metadata) {
        // Default implementation: just broadcast on the channel
        broadcast(channel, data);
    }

    /**
     * Gets the transport type identifier.
     *
     * @return transport type (e.g., "redis", "rabbitmq")
     */
    String getType();

    @Override
    void close() throws Exception;
}