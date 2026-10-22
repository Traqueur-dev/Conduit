package fr.traqueur.conduit.rabbitmq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import fr.traqueur.conduit.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * RabbitMQ transport.
 *
 * <p>Broadcast goes through a fanout exchange {@code conduit.broadcast.<channel>}, each subscriber
 * with its own exclusive queue; unicast through a direct exchange {@code conduit.direct.<channel>}
 * with the instance id as routing key. Two channels, one to publish and one to consume, each
 * guarded by its own lock. Operations are blocking: the returned futures are already complete.</p>
 *
 * @author Traqueur
 */
public class RabbitMQTransport implements Transport {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMQTransport.class);

    private final RabbitMQConfig config;
    private Connection connection;
    private Channel consumeChannel;
    private Channel publishChannel;
    private final Object publishLock = new Object();
    private final Object consumeLock = new Object();

    // Cache of declared exchanges to avoid redundant round-trips
    private final Set<String> declaredExchanges = ConcurrentHashMap.newKeySet();

    // Active subscriptions, by key: "b:<channel>" or "u:<channel>:<instanceId>"
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * Creates a new RabbitMQ transport.
     *
     * @param config the RabbitMQ configuration
     */
    public RabbitMQTransport(RabbitMQConfig config) {
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(config.host());
        factory.setPort(config.port());
        factory.setUsername(config.username());
        factory.setPassword(config.password());
        factory.setVirtualHost(config.virtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);

        connection = factory.newConnection();
        consumeChannel = connection.createChannel();
        publishChannel = connection.createChannel();

        LOGGER.info("Connected to RabbitMQ at {}:{}", config.host(), config.port());
    }

    @Override
    public CompletableFuture<Void> broadcast(String channel, byte[] data) {
        return publish("conduit.broadcast." + channel, BuiltinExchangeType.FANOUT, "", data);
    }

    @Override
    public CompletableFuture<Void> sendTo(String channel, String targetId, byte[] data) {
        return publish("conduit.direct." + channel, BuiltinExchangeType.DIRECT, targetId, data);
    }

    @Override
    public CompletableFuture<Void> subscribe(String channel, BiConsumer<String, byte[]> handler) {
        String exchange = "conduit.broadcast." + channel;
        return consume("b:" + channel, () -> {
            ensureExchange(exchange, BuiltinExchangeType.FANOUT);
            // Exclusive, server-named: deleted with the connection.
            String queue = consumeChannel.queueDeclare().getQueue();
            consumeChannel.queueBind(queue, exchange, "");
            return queue;
        }, channel, handler);
    }

    @Override
    public CompletableFuture<Void> subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler) {
        String exchange = "conduit.direct." + channel;
        return consume("u:" + channel + ":" + instanceId, () -> {
            ensureExchange(exchange, BuiltinExchangeType.DIRECT);
            // Durable for RabbitMQ 4.x (transient non-exclusive queues are deprecated), auto-deleted.
            String queue = "conduit.unicast." + channel + "." + instanceId;
            consumeChannel.queueDeclare(queue, true, false, true, null);
            consumeChannel.queueBind(queue, exchange, instanceId);
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
        return connection != null && connection.isOpen();
    }

    @Override
    public String getType() {
        return "rabbitmq";
    }

    @Override
    public void close() throws Exception {
        subscriptions.clear();
        if (consumeChannel != null && consumeChannel.isOpen()) {
            consumeChannel.close();
        }
        if (publishChannel != null && publishChannel.isOpen()) {
            publishChannel.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        LOGGER.info("RabbitMQ transport closed");
    }

    private CompletableFuture<Void> publish(String exchange, BuiltinExchangeType type, String routingKey, byte[] data) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RabbitMQ transport is not connected"));
        }
        try {
            synchronized (publishLock) {
                ensureExchange(exchange, type);
                publishChannel.basicPublish(exchange, routingKey, null, data);
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<Void> consume(String key, QueueSetup setup, String logical, BiConsumer<String, byte[]> handler) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("RabbitMQ transport is not connected"));
        }
        try {
            synchronized (consumeLock) {
                Subscription previous = subscriptions.remove(key);
                if (previous != null) {
                    drop(previous);
                }
                String queue = setup.declare();
                String tag = consumeChannel.basicConsume(queue, true, deliver(logical, handler),
                        consumerTag -> LOGGER.debug("Consumer cancelled: {}", consumerTag));
                subscriptions.put(key, new Subscription(queue, tag));
                LOGGER.debug("Subscribed {} via queue {}", key, queue);
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<Void> cancel(String key) {
        Subscription subscription = subscriptions.remove(key);
        if (subscription == null || consumeChannel == null || !consumeChannel.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            synchronized (consumeLock) {
                drop(subscription);
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void drop(Subscription subscription) throws IOException {
        consumeChannel.basicCancel(subscription.consumerTag());
        consumeChannel.queueDelete(subscription.queue());
    }

    /**
     * Declares an exchange once per session, on whichever channel the caller holds the lock of.
     */
    private void ensureExchange(String name, BuiltinExchangeType type) throws IOException {
        if (!declaredExchanges.contains(name)) {
            synchronized (publishLock) {
                publishChannel.exchangeDeclare(name, type, true);
            }
            declaredExchanges.add(name); // Only once declared: a failed declaration is retried next time.
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
        String declare() throws IOException;
    }

    private record Subscription(String queue, String consumerTag) {}
}
