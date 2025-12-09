package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.PacketEnvelope;
import io.lettuce.core.pubsub.RedisPubSubAdapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Redis PubSub listener for handling acknowledgment responses.
 * Listens on a temporary ACK channel and completes the corresponding future
 * when an acknowledgment is received.
 *
 * @author Traqueur
 */
public class RedisAckListener extends RedisPubSubAdapter<String, byte[]> {

        private final String expectedChannel;
        private final String ackId;
        private final Map<String, CompletableFuture<AckResponse>> pendingAcks;

        /**
         * Creates a new ACK listener.
         *
         * @param expectedChannel the channel to listen on
         * @param ackId the acknowledgment ID to match
         * @param pendingAcks map of pending acknowledgment futures
         */
        public RedisAckListener(String expectedChannel, String ackId,
                                Map<String, CompletableFuture<AckResponse>> pendingAcks) {
            this.expectedChannel = expectedChannel;
            this.ackId = ackId;
            this.pendingAcks = pendingAcks;
        }

        /**
         * Handles incoming messages on the subscribed channel.
         * Deserializes the ACK response and completes the corresponding future.
         *
         * @param channel the channel the message was received on
         * @param message the message data
         */
        @Override
        public void message(String channel, byte[] message) {
            if (channel.equals(expectedChannel)) {
                CompletableFuture<AckResponse> future = pendingAcks.remove(ackId);

                if (future != null) {
                    try {
                        // Deserialize envelope to extract AckResponse
                        PacketEnvelope envelope = PacketEnvelope.fromBytes(message);

                        if (envelope.isAckResponse() && envelope.ackResponse() != null) {
                            future.complete(envelope.ackResponse());
                        } else {
                            future.completeExceptionally(
                                    new RuntimeException("Invalid ACK response received")
                            );
                        }

                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                }
            }
        }
    }