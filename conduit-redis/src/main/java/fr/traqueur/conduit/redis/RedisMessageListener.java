package fr.traqueur.conduit.redis;

import io.lettuce.core.pubsub.RedisPubSubAdapter;

import java.util.function.BiConsumer;

/**
 * Listener for Redis PUB/SUB messages.
 * Handles incoming messages on broadcast and unicast channels.
 *
 * @author Traqueur
 */
class RedisMessageListener extends RedisPubSubAdapter<String, byte[]> {

    private final String expectedChannel;
    private final BiConsumer<String, byte[]> handler;

    /**
     * Creates a new message listener.
     *
     * @param expectedChannel the base channel to listen on
     * @param handler the callback to invoke when messages are received
     */
    public RedisMessageListener(String expectedChannel, BiConsumer<String, byte[]> handler) {
        this.expectedChannel = expectedChannel;
        this.handler = handler;
    }

    /**
     * Handles incoming PubSub messages.
     * Accepts messages from the exact channel or sub-channels (for unicast routing).
     *
     * @param channel the channel the message was received on
     * @param message the message data
     */
    @Override
    public void message(String channel, byte[] message) {
        if (channel.equals(expectedChannel) || channel.startsWith(expectedChannel + ":")) {
            handler.accept(channel, message);
        }
    }
}