package fr.traqueur.conduit.redis;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

import java.util.function.BiConsumer;

/**
 * Listener for Redis PUB/SUB messages.
 *
 * @author Traqueur
 */
class RedisMessageListener extends RedisPubSubAdapter<String, byte[]> {
    
    private final String expectedChannel;
    private final BiConsumer<String, byte[]> handler;
    
    public RedisMessageListener(String expectedChannel, BiConsumer<String, byte[]> handler) {
        this.expectedChannel = expectedChannel;
        this.handler = handler;
    }
    
    @Override
    public void message(String channel, byte[] message) {
        if (channel.equals(expectedChannel) || channel.startsWith(expectedChannel + ":")) {
            handler.accept(channel, message);
        }
    }
}