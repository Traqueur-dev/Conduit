package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.transport.Transport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import io.lettuce.core.resource.DefaultClientResources;
import io.netty.resolver.DefaultAddressResolverGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Redis Pub/Sub transport (Lettuce, async).
 *
 * <p>Broadcast publishes on {@code <prefix><channel>}, unicast on
 * {@code <prefix><channel>:<instanceId>}. The transport opens two connections (commands and
 * Pub/Sub) and a single listener that routes each message by channel.</p>
 *
 * <pre>
 * new RedisTransport(RedisConfig.of("localhost", 6379));                  // its own client
 * new RedisTransport(RedisURI.create("rediss://user:pw@redis:6380/0"));   // ACL user, TLS, timeout...
 * new RedisTransport(applicationClient);                                  // the application's client
 * RedisTransport.builder().client(applicationClient).channelPrefix("valkn-prod:").build();
 * </pre>
 *
 * <p>A client passed in is never closed nor shut down by {@link #close()}, and neither are its
 * {@code ClientResources}: only the connections this transport opened are closed.</p>
 *
 * @author Traqueur
 */
public class RedisTransport implements Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTransport.class);
    private static final RedisCodec<String, byte[]> CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

    private final RedisURI uri;
    private final RedisClient providedClient;
    private final String channelPrefix;

    private DefaultClientResources ownedResources;
    private RedisClient ownedClient;
    private StatefulRedisConnection<String, byte[]> connection;
    private StatefulRedisPubSubConnection<String, byte[]> pubSubConnection;
    private RedisAsyncCommands<String, byte[]> commands;
    private RedisPubSubAsyncCommands<String, byte[]> pubSub;

    private final Map<String, Route> routes = new ConcurrentHashMap<>();

    /**
     * A transport that opens its own client.
     *
     * @param config the connection settings
     */
    public RedisTransport(RedisConfig config) {
        this(uriOf(config), null, "");
    }

    /**
     * A transport that opens its own client from a URI (ACL user, TLS, timeout, sentinel...).
     *
     * @param uri the Redis URI
     */
    public RedisTransport(RedisURI uri) {
        this(Objects.requireNonNull(uri, "uri"), null, "");
    }

    /**
     * A transport on a client the application owns, which this transport never closes.
     *
     * @param client the client
     */
    public RedisTransport(RedisClient client) {
        this(null, Objects.requireNonNull(client, "client"), "");
    }

    private RedisTransport(RedisURI uri, RedisClient providedClient, String channelPrefix) {
        this.uri = uri;
        this.providedClient = providedClient;
        this.channelPrefix = channelPrefix == null ? "" : channelPrefix;
    }

    /**
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The URI a config describes, built without opening anything. Package-private so a test can
     * assert what this transport will actually dial.
     *
     * <p>TLS is a property of the PORT: a Redis published with {@code tls-port} refuses a
     * cleartext handshake, and a cleartext port refuses a TLS one.
     */
    static RedisURI uriOf(RedisConfig config) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(config.host())
                .withPort(config.port())
                .withDatabase(config.database());
        if (config.password() != null && !config.password().isEmpty()) {
            if (config.username() != null && !config.username().isEmpty()) {
                builder.withAuthentication(config.username(), config.password().toCharArray());
            } else {
                builder.withPassword(config.password().toCharArray());
            }
        }
        builder.withSsl(config.ssl());
        // Only ever RELAXED, never tightened: Lettuce verifies by default, so this line can only
        // take a guarantee away. Written as its own guarded statement so that reading it is
        // reading a decision.
        if (config.ssl() && !config.verifyPeer()) {
            builder.withVerifyPeer(false);
        }
        if (config.timeout() != null) {
            builder.withTimeout(config.timeout());
        }
        return builder.build();
    }

    @Override
    public void connect() {
        RedisClient client;
        if (providedClient != null) {
            client = providedClient;
        } else {
            // JVM DNS resolver instead of Netty's async DnsAddressResolverGroup: avoids classloader
            // conflicts when several plugins each bundle Lettuce in isolated PluginClassLoaders (Paper).
            ownedResources = DefaultClientResources.builder()
                    .addressResolverGroup(DefaultAddressResolverGroup.INSTANCE)
                    .build();
            ownedClient = RedisClient.create(ownedResources, uri);
            client = ownedClient;
        }
        connection = client.connect(CODEC);
        pubSubConnection = client.connectPubSub(CODEC);
        pubSubConnection.addListener(new Dispatcher());
        commands = connection.async();
        pubSub = pubSubConnection.async();

        if (uri != null) {
            LOGGER.info("Connected to Redis at {}:{} (TLS: {}{}), channel prefix '{}'", uri.getHost(), uri.getPort(),
                    uri.isSsl(), uri.isSsl() && !uri.isVerifyPeer() ? ", peer NOT verified" : "", channelPrefix);
        } else {
            LOGGER.info("Connected to Redis through the provided client, channel prefix '{}'", channelPrefix);
        }
    }

    @Override
    public CompletableFuture<Void> broadcast(String channel, byte[] data) {
        return publish(channelPrefix + channel, data);
    }

    @Override
    public CompletableFuture<Void> sendTo(String channel, String targetId, byte[] data) {
        return publish(channelPrefix + channel + ":" + targetId, data);
    }

    @Override
    public CompletableFuture<Void> subscribe(String channel, BiConsumer<String, byte[]> handler) {
        return subscribePhysical(channelPrefix + channel, channel, handler);
    }

    @Override
    public CompletableFuture<Void> subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler) {
        return subscribePhysical(channelPrefix + channel + ":" + instanceId, channel, handler);
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String channel) {
        return unsubscribePhysical(channelPrefix + channel);
    }

    @Override
    public CompletableFuture<Void> unsubscribeUnicast(String channel, String instanceId) {
        return unsubscribePhysical(channelPrefix + channel + ":" + instanceId);
    }

    @Override
    public boolean isConnected() {
        return connection != null && connection.isOpen() && pubSubConnection != null && pubSubConnection.isOpen();
    }

    @Override
    public String channelPrefix() {
        return channelPrefix;
    }

    @Override
    public String getType() {
        return "redis";
    }

    /**
     * Closes the connections this transport opened, and the client and resources only if it
     * created them.
     */
    @Override
    public void close() {
        if (pubSubConnection != null) {
            pubSubConnection.close();
        }
        if (connection != null) {
            connection.close();
        }
        routes.clear();
        if (ownedClient != null) {
            ownedClient.shutdown();
        }
        if (ownedResources != null) {
            ownedResources.shutdown();
        }
        LOGGER.info("Redis transport closed");
    }

    private CompletableFuture<Void> publish(String physical, byte[] data) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Redis transport is not connected"));
        }
        return done(commands.publish(physical, data));
    }

    private CompletableFuture<Void> subscribePhysical(String physical, String logical, BiConsumer<String, byte[]> handler) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Redis transport is not connected"));
        }
        Route route = new Route(logical, handler);
        routes.put(physical, route);
        CompletableFuture<Void> subscribed = done(pubSub.subscribe(physical));
        subscribed.whenComplete((v, error) -> {
            if (error != null) {
                routes.remove(physical, route);
            } else {
                LOGGER.debug("Subscribed to {}", physical);
            }
        });
        return subscribed;
    }

    private CompletableFuture<Void> unsubscribePhysical(String physical) {
        routes.remove(physical);
        if (!isConnected()) {
            return CompletableFuture.completedFuture(null); // Nothing is subscribed on a closed connection.
        }
        return done(pubSub.unsubscribe(physical));
    }

    private static CompletableFuture<Void> done(CompletionStage<?> stage) {
        return stage.toCompletableFuture().thenApply(v -> null);
    }

    private record Route(String logical, BiConsumer<String, byte[]> handler) {}

    /** The only listener: routes each message to the handler of its channel. */
    private final class Dispatcher extends RedisPubSubAdapter<String, byte[]> {
        @Override
        public void message(String channel, byte[] message) {
            Route route = routes.get(channel);
            if (route == null) {
                return; // Unsubscribed while the message was in flight.
            }
            try {
                route.handler.accept(route.logical, message);
            } catch (RuntimeException e) {
                // Never let a handler break Lettuce's event loop.
                LOGGER.error("Handler of Redis channel {} threw", channel, e);
            }
        }
    }

    /**
     * Builder of {@link RedisTransport}: exactly one of {@link #config}, {@link #uri} or
     * {@link #client}.
     */
    public static final class Builder {
        private RedisURI uri;
        private RedisClient client;
        private String channelPrefix = "";

        private Builder() {
        }

        /**
         * @param config settings of a client the transport opens and owns
         * @return this builder
         */
        public Builder config(RedisConfig config) {
            this.uri = uriOf(config);
            return this;
        }

        /**
         * @param uri URI of a client the transport opens and owns
         * @return this builder
         */
        public Builder uri(RedisURI uri) {
            this.uri = uri;
            return this;
        }

        /**
         * @param client a client the application owns; never closed by the transport
         * @return this builder
         */
        public Builder client(RedisClient client) {
            this.client = client;
            return this;
        }

        /**
         * A prefix for every channel (broadcast, unicast, ACK), so that environments sharing one
         * Redis do not see each other. Signed into every envelope.
         *
         * @param channelPrefix the prefix, e.g. {@code "valkn-prod:"}
         * @return this builder
         */
        public Builder channelPrefix(String channelPrefix) {
            this.channelPrefix = channelPrefix;
            return this;
        }

        /**
         * @return the transport
         * @throws IllegalStateException unless exactly one of config, uri or client was given
         */
        public RedisTransport build() {
            if ((uri == null) == (client == null)) {
                throw new IllegalStateException("Give exactly one of config(), uri() or client()");
            }
            return new RedisTransport(uri, client, channelPrefix);
        }
    }
}
