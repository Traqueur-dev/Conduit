package fr.traqueur.conduit.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters of one {@link Conduit}, read without locking. Every counter
 * is monotonic over the life of the instance.
 *
 * <ul>
 *   <li>{@link #sent()}: packets the transport accepted (ACK requests included, ACK responses not)</li>
 *   <li>{@link #received()}: packets accepted and decoded for a registered type</li>
 *   <li>{@link #sendFailures()}: sends whose future failed</li>
 *   <li>{@link #handlerFailures()}: handlers that threw, failed, or whose executor refused the task</li>
 *   <li>{@link #refused()}: envelopes rejected as malformed, misrouted, unsigned, badly signed,
 *       out of clock skew, replayed, or refused by the verifier</li>
 *   <li>{@link #ackTimeouts()}: ACK requests that expired</li>
 *   <li>{@link #unknownType()}: packets for a wire name that is not (or no longer) registered</li>
 * </ul>
 *
 * <p>{@link #byWire()} holds the same counters per wire name, for wire names registered at least
 * once on this instance only: a wire name nobody registered goes to {@link #unknownType()}, so a
 * sender cannot grow the map.</p>
 *
 * @author Traqueur
 */
public final class ConduitMetrics {

    private final Counters total = new Counters();
    private final LongAdder unknownType = new LongAdder();
    private final Map<String, Counters> byWire = new ConcurrentHashMap<>();

    ConduitMetrics() {
    }

    /** @return packets sent */
    public long sent() { return total.sent.sum(); }

    /** @return packets received */
    public long received() { return total.received.sum(); }

    /** @return failed sends */
    public long sendFailures() { return total.sendFailures.sum(); }

    /** @return failed handlers */
    public long handlerFailures() { return total.handlerFailures.sum(); }

    /** @return refused envelopes */
    public long refused() { return total.refused.sum(); }

    /** @return expired ACK requests */
    public long ackTimeouts() { return total.ackTimeouts.sum(); }

    /** @return packets of an unknown wire name */
    public long unknownType() { return unknownType.sum(); }

    /**
     * @return a snapshot of the counters per wire name, ordered by name
     */
    public Map<String, Counts> byWire() {
        Map<String, Counts> snapshot = new LinkedHashMap<>();
        byWire.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> snapshot.put(e.getKey(), e.getValue().snapshot()));
        return Collections.unmodifiableMap(snapshot);
    }

    // ===== Recording (used by Conduit) =====

    /** @param wire a newly registered wire name */
    void track(String wire) { byWire.computeIfAbsent(wire, w -> new Counters()); }

    /** @param wire the wire name, or null */
    void recordSent(String wire) { total.sent.increment(); wire(wire, c -> c.sent); }

    /** @param wire the wire name, or null */
    void recordReceived(String wire) { total.received.increment(); wire(wire, c -> c.received); }

    /** @param wire the wire name, or null */
    void recordSendFailure(String wire) { total.sendFailures.increment(); wire(wire, c -> c.sendFailures); }

    /** @param wire the wire name, or null */
    void recordHandlerFailure(String wire) { total.handlerFailures.increment(); wire(wire, c -> c.handlerFailures); }

    /** @param wire the wire name, or null */
    void recordRefused(String wire) { total.refused.increment(); wire(wire, c -> c.refused); }

    /** @param wire the wire name, or null */
    void recordAckTimeout(String wire) { total.ackTimeouts.increment(); wire(wire, c -> c.ackTimeouts); }

    /** Records a packet of an unknown wire name. */
    void recordUnknownType() { unknownType.increment(); }

    private void wire(String wire, java.util.function.Function<Counters, LongAdder> counter) {
        if (wire == null) return;
        Counters c = byWire.get(wire);
        if (c != null) counter.apply(c).increment();
    }

    @Override
    public String toString() {
        return "ConduitMetrics[sent=" + sent() + ", received=" + received() + ", sendFailures=" + sendFailures()
                + ", handlerFailures=" + handlerFailures() + ", refused=" + refused()
                + ", ackTimeouts=" + ackTimeouts() + ", unknownType=" + unknownType() + "]";
    }

    /**
     * Counters of one wire name, at the time of the snapshot.
     *
     * @param sent            packets sent
     * @param received        packets received
     * @param sendFailures    failed sends
     * @param handlerFailures failed handlers
     * @param refused         refused envelopes
     * @param ackTimeouts     expired ACK requests
     */
    public record Counts(long sent, long received, long sendFailures, long handlerFailures,
                         long refused, long ackTimeouts) {}

    private static final class Counters {
        final LongAdder sent = new LongAdder();
        final LongAdder received = new LongAdder();
        final LongAdder sendFailures = new LongAdder();
        final LongAdder handlerFailures = new LongAdder();
        final LongAdder refused = new LongAdder();
        final LongAdder ackTimeouts = new LongAdder();

        Counts snapshot() {
            return new Counts(sent.sum(), received.sum(), sendFailures.sum(), handlerFailures.sum(),
                    refused.sum(), ackTimeouts.sum());
        }
    }
}
