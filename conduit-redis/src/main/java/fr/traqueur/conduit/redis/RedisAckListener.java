package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.PacketEnvelope;
import io.lettuce.core.pubsub.RedisPubSubAdapter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RedisAckListener extends RedisPubSubAdapter<String, byte[]> {

        private final String expectedChannel;
        private final String ackId;
        private final Map<String, CompletableFuture<AckResponse>> pendingAcks;

        public RedisAckListener(String expectedChannel, String ackId,
                                Map<String, CompletableFuture<AckResponse>> pendingAcks) {
            this.expectedChannel = expectedChannel;
            this.ackId = ackId;
            this.pendingAcks = pendingAcks;
        }

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