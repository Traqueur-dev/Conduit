package fr.traqueur.conduit.transport;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * A messaging backend (Redis, RabbitMQ, in-memory...). It only moves opaque bytes on named
 * channels: routing of ACKs, signatures and dispatch all live in
 * {@link fr.traqueur.conduit.core.Conduit}.
 *
 * <p>Every channel name given to a transport is logical; a transport that supports a
 * {@linkplain #channelPrefix() prefix} applies it to every physical name, in both directions.</p>
 *
 * <p>Handlers passed to {@code subscribe*} are called on the transport's own thread; a transport
 * must catch whatever a handler throws so it never breaks its delivery loop.</p>
 *
 * @author Traqueur
 */
public interface Transport extends AutoCloseable {

    /**
     * Connects the transport.
     *
     * @throws Exception if the connection fails
     */
    void connect() throws Exception;

    /**
     * Publishes to every subscriber of a channel.
     *
     * @param channel the logical channel
     * @param data    the bytes
     * @return a future that completes once the backend accepted the message, and fails if it did not
     *         (disconnected, refused, ...)
     */
    CompletableFuture<Void> broadcast(String channel, byte[] data);

    /**
     * Publishes to the single instance subscribed with {@link #subscribeUnicast} under {@code targetId}.
     *
     * @param channel  the logical channel
     * @param targetId the target instance id
     * @param data     the bytes
     * @return a future, as for {@link #broadcast}
     */
    CompletableFuture<Void> sendTo(String channel, String targetId, byte[] data);

    /**
     * Subscribes to the broadcasts of a channel. Subscribing a channel twice replaces the handler.
     *
     * @param channel the logical channel
     * @param handler called with (logical channel, bytes)
     * @return a future that completes once the subscription is active
     */
    CompletableFuture<Void> subscribe(String channel, BiConsumer<String, byte[]> handler);

    /**
     * Subscribes to the messages sent to {@code instanceId} on a channel.
     *
     * @param channel    the logical channel
     * @param instanceId this instance id
     * @param handler    called with (logical channel, bytes)
     * @return a future that completes once the subscription is active
     */
    CompletableFuture<Void> subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler);

    /**
     * Stops receiving the broadcasts of a channel.
     *
     * @param channel the logical channel
     * @return a future that completes once unsubscribed
     */
    CompletableFuture<Void> unsubscribe(String channel);

    /**
     * Stops receiving the messages sent to {@code instanceId} on a channel.
     *
     * @param channel    the logical channel
     * @param instanceId this instance id
     * @return a future that completes once unsubscribed
     */
    CompletableFuture<Void> unsubscribeUnicast(String channel, String instanceId);

    /**
     * @return whether the transport is connected
     */
    boolean isConnected();

    /**
     * The prefix applied to every physical channel name, so that environments sharing a backend
     * do not see each other. Signed into every envelope.
     *
     * @return the prefix, {@code ""} when none
     */
    default String channelPrefix() {
        return "";
    }

    /**
     * @return transport type (e.g. "redis", "rabbitmq", "memory")
     */
    String getType();

    @Override
    void close() throws Exception;
}
