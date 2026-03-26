package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.PacketEnvelope;
import fr.traqueur.conduit.transport.Transport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Redis transport implementation using Lettuce (async).
 *
 * <p>Redis is naturally a broadcast system (PUB/SUB).
 * For unicast, we use channel suffixes: channel:instanceId</p>
 *
 * @author Traqueur
 */
public class RedisTransport implements Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTransport.class);

    private final RedisConfig config;
    private RedisClient client;
    private StatefulRedisConnection<String, byte[]> connection;
    private StatefulRedisPubSubConnection<String, byte[]> pubSubConnection;
    private RedisAsyncCommands<String, byte[]> asyncCommands;
    private RedisPubSubAsyncCommands<String, byte[]> pubSubAsyncCommands;

    // Pour gérer les ACK avec Redis (on utilise des channels temporaires)
    private final Map<String, CompletableFuture<AckResponse>> pendingAcks = new ConcurrentHashMap<>();

    /**
     * Creates a new Redis transport with the specified configuration.
     *
     * @param config the Redis configuration
     */
    public RedisTransport(RedisConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        RedisURI redisUri = RedisURI.create(config.toUri());
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

        client = RedisClient.create(redisUri);
        connection = client.connect(codec);
        pubSubConnection = client.connectPubSub(codec);

        asyncCommands = connection.async();
        pubSubAsyncCommands = pubSubConnection.async();

        LOGGER.info("Connected to Redis at {}:{}", config.host(), config.port());
    }

    @Override
    public void broadcast(String channel, byte[] data) {
        asyncCommands.publish(channel, data)
                .thenAccept(receivers -> LOGGER.debug("Published to channel: {} ({} receivers)", channel, receivers))
                .exceptionally(error -> {
                    LOGGER.error("Failed to publish to channel: {}", channel, error);
                    return null;
                });
    }

    @Override
    public void sendTo(String channel, String targetId, byte[] data) {
        // Unicast in Redis: publish to channel:targetId
        String unicastChannel = channel + ":" + targetId;
        asyncCommands.publish(unicastChannel, data)
                .thenAccept(receivers -> LOGGER.debug("Published to unicast channel: {} ({} receivers)", unicastChannel, receivers))
                .exceptionally(error -> {
                    LOGGER.error("Failed to publish to unicast channel: {}", unicastChannel, error);
                    return null;
                });
    }

    @Override
    public CompletableFuture<AckResponse> broadcastWithAck(String channel, byte[] data, long timeoutMs) {
        String ackId = UUID.randomUUID().toString();
        String ackChannel = channel + ":ack:" + ackId;

        CompletableFuture<AckResponse> future = new CompletableFuture<>();
        pendingAcks.put(ackId, future);

        // Add listener for ACK channel
        RedisAckListener ackListener = new RedisAckListener(ackChannel, ackId, pendingAcks);
        pubSubConnection.addListener(ackListener);

        // Subscribe to ACK channel then send
        pubSubAsyncCommands.subscribe(ackChannel)
                .thenCompose(v -> {
                    // NOUVEAU: Inject metadata before sending
                    try {
                        PacketEnvelope envelope = PacketEnvelope.fromBytes(data);
                        envelope.metadata().put("redis.ackChannel", ackChannel);
                        byte[] modifiedData = envelope.toBytes();
                        return asyncCommands.publish(channel, modifiedData);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .exceptionally(error -> {
                    future.completeExceptionally(error);
                    pendingAcks.remove(ackId);
                    return null;
                });

        // Timeout handling — attach cleanup to original future, not the orTimeout derivative
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> {
            pendingAcks.remove(ackId);
            pubSubAsyncCommands.unsubscribe(ackChannel);
            pubSubConnection.removeListener(ackListener);
        });

        return future;
    }

    @Override
    public CompletableFuture<AckResponse> sendToWithAck(String channel, String targetId, byte[] data, long timeoutMs) {
        String ackId = UUID.randomUUID().toString();
        String ackChannel = channel + ":ack:" + ackId;

        CompletableFuture<AckResponse> future = new CompletableFuture<>();
        pendingAcks.put(ackId, future);

        String unicastChannel = channel + ":" + targetId;

        // Add listener for ACK channel
        RedisAckListener ackListener = new RedisAckListener(ackChannel, ackId, pendingAcks);
        pubSubConnection.addListener(ackListener);

        // Subscribe to ACK channel then send
        pubSubAsyncCommands.subscribe(ackChannel)
                .thenCompose(v -> {
                    // NOUVEAU: Inject metadata before sending
                    try {
                        PacketEnvelope envelope = PacketEnvelope.fromBytes(data);
                        envelope.metadata().put("redis.ackChannel", ackChannel);
                        byte[] modifiedData = envelope.toBytes();
                        return asyncCommands.publish(unicastChannel, modifiedData);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .exceptionally(error -> {
                    future.completeExceptionally(error);
                    pendingAcks.remove(ackId);
                    return null;
                });

        // Timeout handling — attach cleanup to original future, not the orTimeout derivative
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        future.whenComplete((result, error) -> {
            pendingAcks.remove(ackId);
            pubSubAsyncCommands.unsubscribe(ackChannel);
            pubSubConnection.removeListener(ackListener);
        });

        return future;
    }

    @Override
    public void subscribe(String channel, BiConsumer<String, byte[]> handler) {
        pubSubConnection.addListener(new RedisMessageListener(channel, handler));
        pubSubAsyncCommands.subscribe(channel)
                .thenAccept(v -> LOGGER.info("Subscribed to broadcast channel: {}", channel))
                .exceptionally(error -> {
                    LOGGER.error("Failed to subscribe to channel: {}", channel, error);
                    return null;
                });
    }

    @Override
    public void sendAckResponse(String channel, byte[] data, Map<String, String> metadata) {
        // Redis: check if there's an ACK channel in metadata
        String ackChannel = metadata.get("redis.ackChannel");

        if (ackChannel != null) {
            // Send to specific ACK channel
            asyncCommands.publish(ackChannel, data)
                    .thenAccept(receivers -> {
                        LOGGER.debug("Sent ACK to channel: {} (receivers: {})", ackChannel, receivers);
                    })
                    .exceptionally(error -> {
                        LOGGER.error("Failed to send ACK to channel: {}", ackChannel, error);
                        return null;
                    });
        } else {
            // Fallback: broadcast on original channel
            broadcast(channel, data);
        }
    }


    @Override
    public void subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler) {
        String unicastChannel = channel + ":" + instanceId;
        pubSubConnection.addListener(new RedisMessageListener(unicastChannel, handler));
        pubSubAsyncCommands.subscribe(unicastChannel)
                .thenAccept(v -> LOGGER.info("Subscribed to unicast channel: {}", unicastChannel))
                .exceptionally(error -> {
                    LOGGER.error("Failed to subscribe to unicast channel: {}", unicastChannel, error);
                    return null;
                });
    }

    @Override
    public void unsubscribe(String channel) {
        pubSubAsyncCommands.unsubscribe(channel)
                .thenAccept(v -> LOGGER.info("Unsubscribed from channel: {}", channel))
                .exceptionally(error -> {
                    LOGGER.error("Failed to unsubscribe from channel: {}", channel, error);
                    return null;
                });
    }

    @Override
    public boolean isConnected() {
        return connection != null && connection.isOpen();
    }

    @Override
    public String getType() {
        return "redis";
    }

    @Override
    public void close() throws Exception {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        LOGGER.info("Redis transport closed");
    }
}