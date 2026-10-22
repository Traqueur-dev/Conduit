package fr.traqueur.conduit.memory;

import fr.traqueur.conduit.transport.Transport;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * A {@link Transport} on an {@link InMemoryBus}. Obtained from {@link InMemoryBus#transport()}.
 *
 * @author Traqueur
 */
public final class InMemoryTransport implements Transport {

    private final InMemoryBus bus;
    private final String prefix;
    volatile boolean connected;

    InMemoryTransport(InMemoryBus bus, String prefix) {
        this.bus = bus;
        this.prefix = prefix == null ? "" : prefix;
    }

    InMemoryBus bus() {
        return bus;
    }

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public CompletableFuture<Void> broadcast(String channel, byte[] data) {
        return publish(prefix + channel, data);
    }

    @Override
    public CompletableFuture<Void> sendTo(String channel, String targetId, byte[] data) {
        return publish(prefix + channel + ":" + targetId, data);
    }

    @Override
    public CompletableFuture<Void> subscribe(String channel, BiConsumer<String, byte[]> handler) {
        return register(prefix + channel, channel, handler);
    }

    @Override
    public CompletableFuture<Void> subscribeUnicast(String channel, String instanceId, BiConsumer<String, byte[]> handler) {
        return register(prefix + channel + ":" + instanceId, channel, handler);
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String channel) {
        bus.unsubscribe(prefix + channel, this);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unsubscribeUnicast(String channel, String instanceId) {
        bus.unsubscribe(prefix + channel + ":" + instanceId, this);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String channelPrefix() {
        return prefix;
    }

    @Override
    public String getType() {
        return "memory";
    }

    @Override
    public void close() {
        connected = false;
        bus.unsubscribeAll(this);
    }

    private CompletableFuture<Void> publish(String physical, byte[] data) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("In-memory transport is disconnected"));
        }
        bus.publish(physical, data);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> register(String physical, String logical, BiConsumer<String, byte[]> handler) {
        if (!connected) {
            return CompletableFuture.failedFuture(new IllegalStateException("In-memory transport is disconnected"));
        }
        bus.subscribe(physical, logical, this, handler);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public String toString() {
        return "InMemoryTransport[prefix=" + prefix + ", connected=" + connected + "]";
    }
}
