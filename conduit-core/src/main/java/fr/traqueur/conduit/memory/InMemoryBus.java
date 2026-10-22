package fr.traqueur.conduit.memory;

import fr.traqueur.conduit.core.PacketEnvelope;
import fr.traqueur.conduit.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * An in-process stand-in for a Redis server: every {@link InMemoryTransport} created by the same
 * bus sees the others, with the same channel semantics as {@code RedisTransport} (broadcast on
 * {@code channel}, unicast on {@code channel:instanceId}, optional prefix).
 *
 * <pre>
 * InMemoryBus bus = new InMemoryBus();
 * Conduit a = Conduit.builder().transport(bus.transport()).build();
 * Conduit b = Conduit.builder().transport(bus.transport()).build();
 * </pre>
 *
 * <p>{@linkplain #InMemoryBus() Synchronous} by default: a message is delivered to every
 * subscriber, on the publishing thread, before {@code send} returns — tests need no waiting.
 * {@link #async()} delivers on one background thread instead, in publication order, the way a
 * real transport would.</p>
 *
 * <p>Failures are simulated with {@link #disconnect(Transport)}, {@link #dropNext(int)} and
 * {@link #dropAcks(int)}.</p>
 *
 * @author Traqueur
 */
public final class InMemoryBus implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryBus.class);

    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final ExecutorService deliveryThread;
    private final AtomicInteger dropNext = new AtomicInteger();
    private final AtomicInteger dropAcks = new AtomicInteger();

    /**
     * Creates a synchronous bus.
     */
    public InMemoryBus() {
        this(null);
    }

    private InMemoryBus(ExecutorService deliveryThread) {
        this.deliveryThread = deliveryThread;
    }

    /**
     * Creates a bus that delivers on a single background thread, in publication order.
     * {@link #close()} it when done.
     *
     * @return the bus
     */
    public static InMemoryBus async() {
        return new InMemoryBus(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "conduit-memory-bus");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * @return a new transport on this bus, without channel prefix
     */
    public InMemoryTransport transport() {
        return transport("");
    }

    /**
     * @param channelPrefix prefix applied to every channel, as {@code RedisTransport} does
     * @return a new transport on this bus
     */
    public InMemoryTransport transport(String channelPrefix) {
        return new InMemoryTransport(this, channelPrefix);
    }

    /**
     * Cuts a transport off: its sends fail and it receives nothing, until {@link #reconnect}.
     * Its subscriptions are kept, as a Redis client resubscribes on reconnection.
     *
     * @param transport a transport of this bus
     */
    public void disconnect(Transport transport) {
        own(transport).connected = false;
    }

    /**
     * Reconnects a transport cut off by {@link #disconnect}.
     *
     * @param transport a transport of this bus
     */
    public void reconnect(Transport transport) {
        own(transport).connected = true;
    }

    /**
     * Silently loses the next {@code n} messages, whatever they are: the publisher sees a
     * successful send, as with a message lost after the backend accepted it.
     *
     * @param n how many
     */
    public void dropNext(int n) {
        dropNext.set(n);
    }

    /**
     * Silently loses the next {@code n} ACK responses, so the requests they answer time out.
     *
     * @param n how many
     */
    public void dropAcks(int n) {
        dropAcks.set(n);
    }

    @Override
    public void close() {
        if (deliveryThread != null) {
            deliveryThread.shutdown();
        }
    }

    // ===== Used by InMemoryTransport =====

    void subscribe(String physical, String logical, InMemoryTransport owner, BiConsumer<String, byte[]> handler) {
        List<Subscription> subs = subscriptions.computeIfAbsent(physical, k -> new CopyOnWriteArrayList<>());
        subs.removeIf(s -> s.owner == owner);
        subs.add(new Subscription(owner, logical, handler));
    }

    void unsubscribe(String physical, InMemoryTransport owner) {
        List<Subscription> subs = subscriptions.get(physical);
        if (subs != null) {
            subs.removeIf(s -> s.owner == owner);
        }
    }

    void unsubscribeAll(InMemoryTransport owner) {
        subscriptions.values().forEach(subs -> subs.removeIf(s -> s.owner == owner));
    }

    void publish(String physical, byte[] data) {
        if (consume(dropNext)) {
            LOGGER.debug("Dropped message on {} (dropNext)", physical);
            return;
        }
        if (dropAcks.get() > 0 && isAckResponse(data) && consume(dropAcks)) {
            LOGGER.debug("Dropped ACK response on {} (dropAcks)", physical);
            return;
        }
        byte[] copy = data.clone();
        Runnable delivery = () -> deliver(physical, copy);
        if (deliveryThread == null) {
            delivery.run();
        } else {
            deliveryThread.execute(delivery);
        }
    }

    private void deliver(String physical, byte[] data) {
        List<Subscription> subs = subscriptions.get(physical);
        if (subs == null) {
            return;
        }
        for (Subscription sub : subs) {
            if (!sub.owner.connected) {
                continue;
            }
            try {
                sub.handler.accept(sub.logical, data.clone());
            } catch (RuntimeException e) {
                // A transport never lets a handler break delivery to the other subscribers.
                LOGGER.error("Subscriber of {} threw", physical, e);
            }
        }
    }

    private InMemoryTransport own(Transport transport) {
        if (!(transport instanceof InMemoryTransport t) || t.bus() != this) {
            throw new IllegalArgumentException("Not a transport of this bus: " + transport);
        }
        return t;
    }

    private static boolean consume(AtomicInteger counter) {
        return counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    }

    private static boolean isAckResponse(byte[] data) {
        try {
            return PacketEnvelope.decode(data).isAckResponse();
        } catch (PacketEnvelope.MalformedEnvelopeException e) {
            return false; // Not an envelope at all: certainly not an ACK.
        }
    }

    private record Subscription(InMemoryTransport owner, String logical, BiConsumer<String, byte[]> handler) {}
}
