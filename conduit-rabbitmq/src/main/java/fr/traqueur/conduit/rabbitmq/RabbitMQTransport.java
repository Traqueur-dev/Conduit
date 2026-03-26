package fr.traqueur.conduit.rabbitmq;

import com.rabbitmq.client.*;
import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.PacketEnvelope;
import fr.traqueur.conduit.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * RabbitMQ transport implementation.
 *
 * <p>Uses fanout exchanges for broadcast and direct exchanges for unicast.
 * Maintains separate channels for publishing and consuming to ensure thread-safety,
 * with a lock protecting all publish operations.</p>
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

    // Cache of declared exchanges to avoid redundant round-trips
    private final Set<String> declaredExchanges = ConcurrentHashMap.newKeySet();

    // Queues for receiving messages
    private final Map<String, String> queueNames = new ConcurrentHashMap<>();

    // Pour gérer les ACK
    private final Map<String, CompletableFuture<AckResponse>> pendingAcks = new ConcurrentHashMap<>();

    /**
     * Creates a new RabbitMQ transport with the specified configuration.
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

        // Enable auto-recovery
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);

        connection = factory.newConnection();
        consumeChannel = connection.createChannel();
        publishChannel = connection.createChannel();

        LOGGER.info("Connected to RabbitMQ at {}:{}", config.host(), config.port());
    }

    @Override
    public void broadcast(String channelName, byte[] data) {
        String exchangeName = "conduit.broadcast." + channelName;
        try {
            synchronized (publishLock) {
                ensureExchange(exchangeName, BuiltinExchangeType.FANOUT);
                publishChannel.basicPublish(exchangeName, "", null, data);
            }
            LOGGER.debug("Published to broadcast exchange: {}", exchangeName);
        } catch (IOException e) {
            LOGGER.error("Failed to broadcast message", e);
        }
    }

    @Override
    public void sendTo(String channelName, String targetId, byte[] data) {
        String exchangeName = "conduit.direct." + channelName;
        try {
            synchronized (publishLock) {
                ensureExchange(exchangeName, BuiltinExchangeType.DIRECT);
                publishChannel.basicPublish(exchangeName, targetId, null, data);
            }
            LOGGER.debug("Published to direct exchange: {} with routing key: {}", exchangeName, targetId);
        } catch (IOException e) {
            LOGGER.error("Failed to send unicast message", e);
        }
    }

    @Override
    public CompletableFuture<AckResponse> broadcastWithAck(String channel, byte[] data, long timeoutMs) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<AckResponse> future = new CompletableFuture<>();
        pendingAcks.put(correlationId, future);

        try {
            // Create temporary reply queue and consumer on consumeChannel
            String replyQueue = consumeChannel.queueDeclare().getQueue();
            consumeChannel.basicConsume(replyQueue, true,
                    new AckConsumer(correlationId),
                    consumerTag -> LOGGER.debug("ACK consumer cancelled: {}", consumerTag));

            // Send message with reply-to on publishChannel
            String exchangeName = "conduit.broadcast." + channel;
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .correlationId(correlationId)
                    .replyTo(replyQueue)
                    .build();

            synchronized (publishLock) {
                ensureExchange(exchangeName, BuiltinExchangeType.FANOUT);
                publishChannel.basicPublish(exchangeName, "", props, data);
            }

            LOGGER.debug("Published with ACK to exchange: {} (correlationId: {})", exchangeName, correlationId);

            // Timeout handling — attach cleanup to original future, not the orTimeout derivative
            future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            future.whenComplete((result, error) -> {
                pendingAcks.remove(correlationId);
                try {
                    if (consumeChannel != null && consumeChannel.isOpen()) {
                        consumeChannel.queueDelete(replyQueue);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete reply queue: {}", replyQueue, e);
                }
            });

        } catch (IOException e) {
            future.completeExceptionally(e);
            pendingAcks.remove(correlationId);
        }

        return future;
    }

    @Override
    public void sendAckResponse(String channel, byte[] data, Map<String, String> metadata) {
        // RabbitMQ: check for replyTo queue
        String replyTo = metadata.get("rabbitmq.replyTo");
        String correlationId = metadata.get("rabbitmq.correlationId");

        if (replyTo != null && correlationId != null) {
            // Use RabbitMQ request/reply pattern
            try {
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                        .correlationId(correlationId)
                        .build();

                synchronized (publishLock) {
                    publishChannel.basicPublish("", replyTo, props, data);
                }

                LOGGER.debug("Sent ACK to replyTo queue: {} with correlationId: {}", replyTo, correlationId);

            } catch (IOException e) {
                LOGGER.error("Failed to send ACK to reply queue: {}", replyTo, e);
            }
        } else {
            // Fallback: broadcast on original channel
            broadcast(channel, data);
        }
    }

    @Override
    public CompletableFuture<AckResponse> sendToWithAck(String channel, String targetId, byte[] data, long timeoutMs) {
        String correlationId = UUID.randomUUID().toString();
        CompletableFuture<AckResponse> future = new CompletableFuture<>();
        pendingAcks.put(correlationId, future);

        try {
            // Create temporary reply queue and consumer on consumeChannel
            String replyQueue = consumeChannel.queueDeclare().getQueue();
            consumeChannel.basicConsume(replyQueue, true,
                    new AckConsumer(correlationId),
                    consumerTag -> LOGGER.debug("ACK consumer cancelled: {}", consumerTag));

            // Send message with reply-to on publishChannel
            String exchangeName = "conduit.direct." + channel;
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .correlationId(correlationId)
                    .replyTo(replyQueue)
                    .build();

            synchronized (publishLock) {
                ensureExchange(exchangeName, BuiltinExchangeType.DIRECT);
                publishChannel.basicPublish(exchangeName, targetId, props, data);
            }

            LOGGER.debug("Published with ACK to exchange: {} routing key: {} (correlationId: {})",
                    exchangeName, targetId, correlationId);

            // Timeout handling — attach cleanup to original future, not the orTimeout derivative
            future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
            future.whenComplete((result, error) -> {
                pendingAcks.remove(correlationId);
                try {
                    if (consumeChannel != null && consumeChannel.isOpen()) {
                        consumeChannel.queueDelete(replyQueue);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to delete reply queue: {}", replyQueue, e);
                }
            });

        } catch (IOException e) {
            future.completeExceptionally(e);
            pendingAcks.remove(correlationId);
        }

        return future;
    }

    @Override
    public void subscribe(String channel, BiConsumer<String, byte[]> handler) {
        try {
            String exchangeName = "conduit.broadcast." + channel;

            synchronized (publishLock) {
                ensureExchange(exchangeName, BuiltinExchangeType.FANOUT);
            }

            // Create exclusive queue for this instance (auto-delete when connection closes)
            String queueName = consumeChannel.queueDeclare().getQueue();
            consumeChannel.queueBind(queueName, exchangeName, "");

            // Start consuming
            consumeChannel.basicConsume(queueName, true,
                    new MessageConsumer(channel, handler),
                    consumerTag -> LOGGER.debug("Consumer cancelled: {}", consumerTag));

            LOGGER.info("Subscribed to broadcast channel: {} via queue: {}", channel, queueName);

        } catch (IOException e) {
            LOGGER.error("Failed to subscribe to channel: {}", channel, e);
        }
    }

    @Override
    public void subscribeUnicast(String channelName, String instanceId, BiConsumer<String, byte[]> handler) {
        try {
            String exchangeName = "conduit.direct." + channelName;

            synchronized (publishLock) {
                ensureExchange(exchangeName, BuiltinExchangeType.DIRECT);
            }

            // Create queue with instanceId as routing key
            String queueName = "conduit.unicast." + channelName + "." + instanceId;
            consumeChannel.queueDeclare(queueName, false, false, true, null);
            consumeChannel.queueBind(queueName, exchangeName, instanceId);

            queueNames.put(channelName + ":" + instanceId, queueName);

            // Setup consumer
            consumeChannel.basicConsume(queueName, true,
                    new MessageConsumer(channelName, handler),
                    consumerTag -> LOGGER.debug("Consumer cancelled: {}", consumerTag));

            LOGGER.info("Subscribed to unicast exchange: {} with routing key: {}", exchangeName, instanceId);

        } catch (IOException e) {
            LOGGER.error("Failed to subscribe to unicast channel: {}", channelName, e);
        }
    }

    @Override
    public void unsubscribe(String channelName) {
        String queueName = queueNames.remove(channelName);
        if (queueName != null) {
            try {
                consumeChannel.queueDelete(queueName);
                LOGGER.info("Unsubscribed from channel: {}", channelName);
            } catch (IOException e) {
                LOGGER.error("Failed to unsubscribe from channel: {}", channelName, e);
            }
        }
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

    /**
     * Declares an exchange only if it has not been declared before in this session.
     * Avoids redundant round-trips to the broker for every message.
     * Must be called while holding {@code publishLock}.
     *
     * @param name the exchange name
     * @param type the exchange type
     * @throws IOException if the declaration fails
     */
    private void ensureExchange(String name, BuiltinExchangeType type) throws IOException {
        if (declaredExchanges.add(name)) {
            publishChannel.exchangeDeclare(name, type, true);
        }
    }

    // ===== Inner Classes =====

    /**
     * Consumer for regular messages.
     * Handles both broadcast and unicast packet deliveries,
     * extracting RabbitMQ-specific metadata and passing it to the handler.
     */
    private class MessageConsumer implements DeliverCallback {

        private final String channelName;
        private final BiConsumer<String, byte[]> handler;

        /**
         * Creates a new message consumer.
         *
         * @param channelName the channel name
         * @param handler the callback to invoke with received messages
         */
        public MessageConsumer(String channelName, BiConsumer<String, byte[]> handler) {
            this.channelName = channelName;
            this.handler = handler;
        }

        /**
         * Handles message delivery from RabbitMQ.
         * Extracts replyTo and correlationId properties and injects them
         * into the packet envelope metadata for ACK routing.
         *
         * @param consumerTag the consumer tag
         * @param delivery the message delivery
         */
        @Override
        public void handle(String consumerTag, Delivery delivery) {
            try {
                byte[] body = delivery.getBody();

                // Extract RabbitMQ properties
                String replyTo = delivery.getProperties().getReplyTo();
                String correlationId = delivery.getProperties().getCorrelationId();

                // If this has RabbitMQ ACK metadata, we need to inject it into the envelope
                if (replyTo != null && correlationId != null) {
                    // Deserialize envelope
                    PacketEnvelope envelope = PacketEnvelope.fromBytes(body);

                    // Add RabbitMQ metadata
                    envelope.metadata().put("rabbitmq.replyTo", replyTo);
                    envelope.metadata().put("rabbitmq.correlationId", correlationId);

                    // Re-serialize with metadata
                    body = envelope.toBytes();
                }

                handler.accept(channelName, body);

            } catch (Exception e) {
                LOGGER.error("Failed to handle message", e);
            }
        }
    }

    /**
     * Consumer for ACK responses.
     * Listens on temporary reply queues for acknowledgment responses
     * and completes the corresponding future when a matching correlationId is received.
     */
    private class AckConsumer implements DeliverCallback {

        private final String correlationId;

        /**
         * Creates a new ACK consumer.
         *
         * @param correlationId the correlation ID to match
         */
        public AckConsumer(String correlationId) {
            this.correlationId = correlationId;
        }

        /**
         * Handles ACK response delivery from RabbitMQ.
         * Matches the correlationId and completes the pending future with the ACK response.
         *
         * @param consumerTag the consumer tag
         * @param delivery the ACK response delivery
         */
        @Override
        public void handle(String consumerTag, Delivery delivery) {
            String receivedCorrelationId = delivery.getProperties().getCorrelationId();

            if (correlationId.equals(receivedCorrelationId)) {
                CompletableFuture<AckResponse> future = pendingAcks.remove(correlationId);

                if (future != null) {
                    try {
                        // Deserialize envelope to extract AckResponse
                        byte[] body = delivery.getBody();
                        PacketEnvelope envelope = PacketEnvelope.fromBytes(body);

                        if (envelope.isAckResponse() && envelope.ackResponse() != null) {
                            future.complete(envelope.ackResponse());
                            LOGGER.debug("Received ACK for correlationId: {}", correlationId);
                        } else {
                            future.completeExceptionally(
                                    new RuntimeException("Invalid ACK response received")
                            );
                        }

                    } catch (Exception e) {
                        LOGGER.error("Failed to deserialize ACK response", e);
                        future.completeExceptionally(e);
                    }
                }
            }
        }
    }
}
