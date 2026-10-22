package fr.traqueur.conduit.rabbitmq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConfirmListener;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import fr.traqueur.conduit.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiConsumer;

/**
 * RabbitMQ transport.
 *
 * <p>Every exchange and queue name starts with the {@linkplain #channelPrefix() channel prefix}:</p>
 * <ul>
 *   <li>broadcast: fanout exchange {@code <prefix>conduit.broadcast.<channel>}, one exclusive queue
 *       {@code <prefix>conduit.sub.<channel>.<random>} per subscriber;</li>
 *   <li>unicast: direct exchange {@code <prefix>conduit.direct.<channel>}, routing key = instance id,
 *       queue {@code <prefix>conduit.unicast.<channel>.<instanceId>}.</li>
 * </ul>
 * <p>A RabbitMQ user can thus be restricted to one prefix with a permission regex such as
 * {@code ^valkn-prod:.*}, as a Redis ACL user is with a channel pattern.</p>
 *
 * <pre>
 * new RabbitMQTransport(RabbitMQConfig.localhost());                 // its own connection
 * new RabbitMQTransport(connectionFactory);                          // its own connection, any setting
 * new RabbitMQTransport(applicationConnection);                      // the application's connection
 * RabbitMQTransport.builder().connection(applicationConnection).channelPrefix("valkn-prod:").build();
 * </pre>
 *
 * <p>A connection passed in is never closed by {@link #close()}: only the channels and queues
 * this transport opened are.</p>
 *
 * <p>Publishing uses publisher confirms: the future of {@link #broadcast} and {@link #sendTo}
 * completes when the broker confirms, and fails when it refuses (nack, permission denied, closed
 * channel). Each subscription has its own channel, and declarations run on a short-lived one, so
 * that a refusal — which closes the channel it happens on — never takes other subscriptions down.</p>
 *
 * @author Traqueur
 */
public class RabbitMQTransport implements Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQTransport.class);

    private final ConnectionFactory factory;
    private final Connection providedConnection;
    private final String channelPrefix;

    private volatile Connection connection;
    private final Object publishLock = new Object();
    private PublishChannel publishChannel;

    // Exchanges declared in this session: declaration is a round-trip, done once per name.
    private final Set<String> declaredExchanges = ConcurrentHashMap.newKeySet();

    // Active subscriptions, by key: "b:<channel>" or "u:<channel>:<instanceId>"
    private final Object subscribeLock = new Object();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * A transport that opens and owns its connection.
     *
     * @param config the RabbitMQ configuration
     */
    public RabbitMQTransport(RabbitMQConfig config) {
        this(factoryOf(config), null, "");
    }

    /**
     * A transport that opens and owns its connection from a factory (TLS, timeouts, credentials provider...).
     *
     * @param factory the connection factory
     */
    public RabbitMQTransport(ConnectionFactory factory) {
        this(Objects.requireNonNull(factory, "factory"), null, "");
    }

    /**
     * A transport on a connection the application owns, which this transport never closes.
     *
     * @param connection the connection
     */
    public RabbitMQTransport(Connection connection) {
        this(null, Objects.requireNonNull(connection, "connection"), "");
    }

    private RabbitMQTransport(ConnectionFactory factory, Connection providedConnection, String channelPrefix) {
        this.factory = factory;
        this.providedConnection = providedConnection;
        this.channelPrefix = channelPrefix == null ? "" : channelPrefix;
    }

    /**
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    static ConnectionFactory factoryOf(RabbitMQConfig config) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.host());
        factory.setPort(config.port());
        factory.setUsername(config.username());
        factory.setPassword(config.password());
        factory.setVirtualHost(config.virtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        return factory;
    }

    @Override
    public void connect() throws Exception {
        connection = providedConnection != null ? providedConnection : factory.newConnection();
        LOGGER.info("Connected to RabbitMQ at {}:{}{}, channel prefix '{}'", connection.getAddress(), connection.getPort(),
                providedConnection != null ? " (provided connection)" : "", channelPrefix);
    }

    @Override
    public CompletableFuture<Void> broadcast(String channel, byte[] data) {
        return publish(broadcastExchange(channel), BuiltinExchangeType.FANOUT, "", data);
    }

    @Override
    public CompletableFuture<Void> sendTo(String channel, String targetId, byte[] data) {
        return publish(directExchange(channel), BuiltinExchangeType.DIRECT, targetId, data);
    }

    @Override
    public CompletableFuture<Void> subscribe(String channel, BiConsumer<String, byte[]> handler) {
        String exchange = broadcastExchange(channel);
        return consume("b:" + channel, ch -> {
            declareExchange(exchange, BuiltinExchangeType.FANOUT);
            // Exclusive and auto-deleted, named (not server-named) so that it carries the prefix.
            String queue = channelPrefix + "conduit.sub." + channel + "." + UUID.randomUUID();
            ch.queueDeclare(queue, false, true, true, null);
            ch.queueBind(queue, exchange, "");
            return queue;
        }, channel, handler);
    }

    @Override
    public CompletableFuture<Void> subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler) {
        String exchange = directExchange(channel);
        return consume("u:" + channel + ":" + instanceId, ch -> {
            declareExchange(exchange, BuiltinExchangeType.DIRECT);
            // Durable for RabbitMQ 4.x (transient non-exclusive queues are deprecated), auto-deleted.
            String queue = channelPrefix + "conduit.unicast." + channel + "." + instanceId;
            ch.queueDeclare(queue, true, false, true, null);
            ch.queueBind(queue, exchange, instanceId);
            return queue;
        }, channel, handler);
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String channel) {
        return cancel("b:" + channel);
    }

    @Override
    public CompletableFuture<Void> unsubscribeUnicast(String channel, String instanceId) {
        return cancel("u:" + channel + ":" + instanceId);
    }

    @Override
    public boolean isConnected() {
        Connection c = connection;
        return c != null && c.isOpen();
    }

    @Override
    public String channelPrefix() {
        return channelPrefix;
    }

    @Override
    public String getType() {
        return "rabbitmq";
    }

    /**
     * Deletes this transport's queues and closes its channels, and the connection only if it
     * opened it.
     */
    @Override
    public void close() throws Exception {
        synchronized (subscribeLock) {
            for (String key : Set.copyOf(subscriptions.keySet())) {
                cancel(key);
            }
        }
        synchronized (publishLock) {
            if (publishChannel != null) {
                closeQuietly(publishChannel.channel);
                publishChannel = null;
            }
        }
        if (providedConnection == null && connection != null && connection.isOpen()) {
            connection.close();
        }
        LOGGER.info("RabbitMQ transport closed");
    }

    private String broadcastExchange(String channel) {
        return channelPrefix + "conduit.broadcast." + channel;
    }

    private String directExchange(String channel) {
        return channelPrefix + "conduit.direct." + channel;
    }

    private CompletableFuture<Void> publish(String exchange, BuiltinExchangeType type, String routingKey, byte[] data) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RabbitMQ transport is not connected"));
        }
        synchronized (publishLock) {
            long seqNo = -1;
            PublishChannel pc = null;
            try {
                declareExchange(exchange, type);
                pc = publishChannel();
                CompletableFuture<Void> confirmed = new CompletableFuture<>();
                seqNo = pc.channel.getNextPublishSeqNo();
                pc.unconfirmed.put(seqNo, confirmed);
                pc.channel.basicPublish(exchange, routingKey, null, data);
                return confirmed;
            } catch (IOException | RuntimeException e) {
                if (pc != null && seqNo >= 0) {
                    pc.unconfirmed.remove(seqNo);
                }
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    /** The publishing channel, reopened when the broker closed it (e.g. after a refused publish). */
    private PublishChannel publishChannel() throws IOException {
        if (publishChannel == null || !publishChannel.channel.isOpen()) {
            Channel channel = connection.createChannel();
            channel.confirmSelect();
            PublishChannel pc = new PublishChannel(channel, new ConcurrentSkipListMap<>());
            channel.addConfirmListener(new ConfirmListener() {
                @Override
                public void handleAck(long deliveryTag, boolean multiple) {
                    settle(pc.unconfirmed, deliveryTag, multiple, null);
                }

                @Override
                public void handleNack(long deliveryTag, boolean multiple) {
                    settle(pc.unconfirmed, deliveryTag, multiple, new IOException("RabbitMQ refused the message (nack)"));
                }
            });
            channel.addShutdownListener(cause -> {
                // Whatever was not confirmed when the channel went down is not known to be published.
                pc.unconfirmed.values().forEach(f -> f.completeExceptionally(cause));
                pc.unconfirmed.clear();
                if (!cause.isInitiatedByApplication()) {
                    LOGGER.warn("RabbitMQ publish channel closed: {}", cause.getMessage());
                }
            });
            publishChannel = pc;
        }
        return publishChannel;
    }

    private static void settle(NavigableMap<Long, CompletableFuture<Void>> unconfirmed, long tag, boolean multiple,
                               Throwable error) {
        Map<Long, CompletableFuture<Void>> done = multiple ? unconfirmed.headMap(tag, true) : unconfirmedOne(unconfirmed, tag);
        for (CompletableFuture<Void> f : done.values()) {
            if (error == null) {
                f.complete(null);
            } else {
                f.completeExceptionally(error);
            }
        }
        done.clear();
    }

    private static Map<Long, CompletableFuture<Void>> unconfirmedOne(NavigableMap<Long, CompletableFuture<Void>> unconfirmed, long tag) {
        return unconfirmed.subMap(tag, true, tag, true);
    }

    /** Declares an exchange once per session, on a short-lived channel: a refusal closes only that one. */
    private void declareExchange(String name, BuiltinExchangeType type) throws IOException {
        if (declaredExchanges.contains(name)) {
            return;
        }
        Channel channel = connection.createChannel();
        try {
            channel.exchangeDeclare(name, type, true);
        } finally {
            closeQuietly(channel);
        }
        declaredExchanges.add(name); // Only once declared: a failed declaration is retried next time.
    }

    private CompletableFuture<Void> consume(String key, QueueSetup setup, String logical, BiConsumer<String, byte[]> handler) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RabbitMQ transport is not connected"));
        }
        synchronized (subscribeLock) {
            cancel(key);
            Channel channel = null;
            try {
                channel = connection.createChannel();
                String queue = setup.declare(channel);
                String tag = channel.basicConsume(queue, true, deliver(logical, handler),
                        consumerTag -> LOGGER.debug("Consumer cancelled: {}", consumerTag));
                subscriptions.put(key, new Subscription(channel, queue, tag));
                LOGGER.debug("Subscribed {} via queue {}", key, queue);
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException e) {
                if (channel != null) {
                    closeQuietly(channel);
                }
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private CompletableFuture<Void> cancel(String key) {
        synchronized (subscribeLock) {
            Subscription subscription = subscriptions.remove(key);
            if (subscription == null || !subscription.channel.isOpen()) {
                return CompletableFuture.completedFuture(null);
            }
            try {
                subscription.channel.basicCancel(subscription.consumerTag);
                subscription.channel.queueDelete(subscription.queue);
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            } finally {
                closeQuietly(subscription.channel);
            }
        }
    }

    private static void closeQuietly(Channel channel) {
        if (!channel.isOpen()) {
            return; // Already closed, by the broker (a refusal) or by the connection.
        }
        try {
            channel.close();
        } catch (IOException | RuntimeException | java.util.concurrent.TimeoutException e) {
            LOGGER.debug("Closing RabbitMQ channel {} failed", channel.getChannelNumber(), e);
        }
    }

    private static DeliverCallback deliver(String logical, BiConsumer<String, byte[]> handler) {
        return (consumerTag, delivery) -> {
            try {
                handler.accept(logical, delivery.getBody());
            } catch (RuntimeException e) {
                // Never let a handler kill the consumer.
                LOGGER.error("Handler of RabbitMQ channel {} threw", logical, e);
            }
        };
    }

    @FunctionalInterface
    private interface QueueSetup {
        String declare(Channel channel) throws IOException;
    }

    private record PublishChannel(Channel channel, ConcurrentSkipListMap<Long, CompletableFuture<Void>> unconfirmed) {}

    private record Subscription(Channel channel, String queue, String consumerTag) {}

    /**
     * Builder of {@link RabbitMQTransport}: exactly one of {@link #config}, {@link #connectionFactory}
     * or {@link #connection}.
     */
    public static final class Builder {
        private ConnectionFactory factory;
        private Connection connection;
        private String channelPrefix = "";

        private Builder() {
        }

        /**
         * @param config settings of a connection the transport opens and owns
         * @return this builder
         */
        public Builder config(RabbitMQConfig config) {
            this.factory = factoryOf(config);
            return this;
        }

        /**
         * @param factory factory of a connection the transport opens and owns
         * @return this builder
         */
        public Builder connectionFactory(ConnectionFactory factory) {
            this.factory = factory;
            return this;
        }

        /**
         * @param connection a connection the application owns; never closed by the transport
         * @return this builder
         */
        public Builder connection(Connection connection) {
            this.connection = connection;
            return this;
        }

        /**
         * A prefix for every exchange and queue, so that environments sharing one broker do not see
         * each other. Signed into every envelope.
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
         * @throws IllegalStateException unless exactly one of config, connectionFactory or connection was given
         */
        public RabbitMQTransport build() {
            if ((factory == null) == (connection == null)) {
                throw new IllegalStateException("Give exactly one of config(), connectionFactory() or connection()");
            }
            return new RabbitMQTransport(factory, connection, channelPrefix);
        }
    }
}
