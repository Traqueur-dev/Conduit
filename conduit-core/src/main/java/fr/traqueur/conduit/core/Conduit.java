package fr.traqueur.conduit.core;

import fr.traqueur.conduit.compression.Compressor;
import fr.traqueur.conduit.compression.NoOpCompressor;
import fr.traqueur.conduit.handler.PacketHandler;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.registry.HandlerRegistry;
import fr.traqueur.conduit.registry.PacketRegistry;
import fr.traqueur.conduit.registry.PacketRegistry.Registration;
import fr.traqueur.conduit.security.EnvelopeSigner;
import fr.traqueur.conduit.security.EnvelopeVerifier;
import fr.traqueur.conduit.security.EnvelopeVerifier.Verdict;
import fr.traqueur.conduit.serialization.JsonSerializer;
import fr.traqueur.conduit.serialization.Serializer;
import fr.traqueur.conduit.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Main entry point of Conduit: registers packet types and handlers, sends packets, receives them,
 * and routes acknowledgments.
 *
 * <p>Instances built normally are kept in a static registry: the first becomes the default one
 * ({@link #getInstance()}), used by the default methods of {@link Packet}, and each can be found
 * by name ({@link #getInstance(String)}). An instance built
 * {@linkplain ConduitBuilder#unregistered() unregistered} stays out of that registry.</p>
 *
 * <p>Packet types and handlers may be registered and removed at any time, before or after
 * {@link #start()}, concurrently with reception. Channels are reference-counted: a custom channel
 * is subscribed while at least one registered type uses it.</p>
 *
 * <p>Acknowledgments travel back on a reply channel per instance,
 * {@code <defaultChannel>:ack}, unicast to the requester: it is subscribed once at start, and an
 * ACK request costs a single publish.</p>
 *
 * @author Traqueur
 */
public class Conduit {

    private static final Logger LOGGER = LoggerFactory.getLogger(Conduit.class);

    private static final Map<String, Conduit> instances = new ConcurrentHashMap<>();
    private static volatile Conduit defaultInstance;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final long REFUSAL_LOG_INTERVAL_MS = 60_000L;
    private static final int LOG_KEYS_CAP = 4096;

    private final Transport transport;
    private final Serializer serializer;
    private final Compressor compressor;
    private final String defaultChannel;
    private final String ackChannel;
    private final String instanceId;
    private final String registryKey;
    private final boolean requireWireNames;
    private final Executor handlerExecutor;
    private final EnvelopeSigner signer;
    private final EnvelopeVerifier verifier;
    private final Duration maxClockSkew;
    private final Clock clock;

    private final PacketRegistry packetRegistry = new PacketRegistry();
    private final HandlerRegistry handlerRegistry = new HandlerRegistry();
    private final ConduitMetrics metrics = new ConduitMetrics();

    // Guards channel reference counts and the started/shutdown transitions.
    private final Object lifecycleLock = new Object();
    private final Map<String, ChannelState> channels = new HashMap<>();
    private volatile boolean started;
    private volatile boolean shutdown;

    private final Map<String, PendingAck> pendingAcks = new ConcurrentHashMap<>();
    private final ReplayGuard replayGuard = new ReplayGuard();
    private final Map<String, Long> lastRefusalLog = new ConcurrentHashMap<>();
    private final Set<String> loggedUnknownTypes = ConcurrentHashMap.newKeySet();

    private final BiConsumer<String, byte[]> broadcastHandler = (ch, data) -> handleIncoming(ch, data, true);
    private final BiConsumer<String, byte[]> unicastHandler = (ch, data) -> handleIncoming(ch, data, false);

    private Conduit(ConduitBuilder b, String registryKey) {
        this.transport = b.transport;
        this.serializer = b.serializer;
        this.compressor = b.compressor;
        this.defaultChannel = b.defaultChannel;
        this.ackChannel = b.defaultChannel + ":ack";
        this.instanceId = b.instanceId;
        this.registryKey = registryKey;
        this.requireWireNames = b.requireWireNames;
        this.handlerExecutor = b.handlerExecutor;
        this.signer = b.signer;
        this.verifier = b.verifier;
        this.maxClockSkew = b.maxClockSkew;
        this.clock = b.clock;

        LOGGER.info("Conduit initialized with transport: {}, serializer: {}, compressor: {}, signed: {}, verified: {}",
                transport.getType(), serializer.getType(), compressor.getType(), signer != null, verifier != null);
    }

    /**
     * Gets the default instance: the first one built without {@link ConduitBuilder#unregistered()}.
     *
     * @return the default instance
     * @throws IllegalStateException if there is none
     */
    public static Conduit getInstance() {
        Conduit instance = defaultInstance;
        if (instance == null) {
            throw new IllegalStateException("No default Conduit instance: build one with Conduit.builder()...build() "
                    + "(an instance built with .unregistered() never becomes the default), "
                    + "or send through the instance itself with conduit.send(packet)");
        }
        return instance;
    }

    /**
     * Gets a named instance.
     *
     * @param name the name given with {@link ConduitBuilder#name(String)}, or else the instance id
     * @return the instance
     * @throws IllegalArgumentException if no instance has that name
     */
    public static Conduit getInstance(String name) {
        Conduit conduit = instances.get(name);
        if (conduit == null) {
            throw new IllegalArgumentException("No Conduit instance named: " + name);
        }
        return conduit;
    }

    // ===== Registration =====

    /**
     * Registers a packet type under its {@link PacketMeta#wire() wire name}, or its simple class
     * name when it has none. After {@link #start()}, subscribes its channel if no other type uses
     * it yet.
     *
     * @param packetClass the packet class
     * @return a future that completes once the type's channel is subscribed (immediately before
     *         start, or when the channel already was)
     * @throws IllegalArgumentException if wire names are required and the class has none
     * @throws IllegalStateException    if the wire name belongs to another class, or the class is
     *                                  already registered under another name
     */
    public CompletableFuture<Void> registerPacket(Class<? extends Packet> packetClass) {
        Objects.requireNonNull(packetClass, "packetClass");
        PacketMeta meta = packetClass.getAnnotation(PacketMeta.class);
        String wire;
        if (meta != null && !meta.wire().isEmpty()) {
            wire = meta.wire();
        } else if (requireWireNames) {
            throw new IllegalArgumentException("Packet " + packetClass.getName() + " has no wire name, and this Conduit "
                    + "requires one: annotate it with @PacketMeta(wire = \"...\") or use registerPacket(wireName, class)");
        } else {
            wire = packetClass.getSimpleName();
        }
        return registerPacket(wire, packetClass);
    }

    /**
     * Registers a packet type under an explicit wire name (which takes precedence over
     * {@link PacketMeta#wire()}). Registering the same class under the same name again does nothing.
     *
     * @param wireName    the wire name
     * @param packetClass the packet class
     * @return a future, as for {@link #registerPacket(Class)}
     * @throws IllegalArgumentException if the wire name is blank
     * @throws IllegalStateException    if the wire name belongs to another class, or the class is
     *                                  already registered under another name
     */
    public CompletableFuture<Void> registerPacket(String wireName, Class<? extends Packet> packetClass) {
        Objects.requireNonNull(packetClass, "packetClass");
        if (wireName == null || wireName.isBlank()) {
            throw new IllegalArgumentException("Wire name of " + packetClass.getName() + " must not be blank");
        }
        String channel = channelOf(packetClass);
        if (channel.equals(ackChannel)) {
            throw new IllegalArgumentException("Channel " + ackChannel + " is reserved for acknowledgments");
        }
        synchronized (lifecycleLock) {
            boolean added = packetRegistry.register(wireName, packetClass, channel);
            metrics.track(wireName);
            if (channel.equals(defaultChannel)) {
                return CompletableFuture.completedFuture(null);
            }
            ChannelState state = channels.computeIfAbsent(channel, c -> new ChannelState());
            if (added) {
                state.refs++;
                if (state.refs == 1 && started) {
                    state.subscribed = subscribeChannel(channel);
                }
            }
            LOGGER.debug("Registered packet {} as '{}' on channel {}", packetClass.getName(), wireName, channel);
            return state.subscribed;
        }
    }

    /**
     * Removes a packet type and its handler. Its channel is unsubscribed when no other registered
     * type uses it; the default channel never is. A packet of this type received afterwards is
     * handled as an unknown type.
     *
     * @param packetClass the packet class
     * @return a future that completes once the channel is unsubscribed (immediately if it is kept)
     */
    public CompletableFuture<Void> unregisterPacket(Class<? extends Packet> packetClass) {
        synchronized (lifecycleLock) {
            handlerRegistry.unregisterHandler(packetClass);
            Registration registration = packetRegistry.unregister(packetClass);
            if (registration == null || registration.channel().equals(defaultChannel)) {
                return CompletableFuture.completedFuture(null);
            }
            String channel = registration.channel();
            ChannelState state = channels.get(channel);
            if (--state.refs > 0) {
                return CompletableFuture.completedFuture(null);
            }
            channels.remove(channel);
            LOGGER.debug("Channel {} no longer used, unsubscribing", channel);
            return started ? unsubscribeChannel(channel) : CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Registers the handler of a packet type, replacing any previous one.
     *
     * @param <T>         the packet type
     * @param packetClass the packet class
     * @param handler     the handler
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, PacketHandler<T> handler) {
        handlerRegistry.registerHandler(packetClass, handler);
        LOGGER.debug("Registered handler for: {}", packetClass.getName());
    }

    /**
     * Removes the handler of a packet type. The type stays registered: its packets are then
     * answered with "No handler registered" when they require an ACK.
     *
     * @param packetClass the packet class
     * @return whether a handler was removed
     */
    public boolean unregisterHandler(Class<? extends Packet> packetClass) {
        return handlerRegistry.unregisterHandler(packetClass);
    }

    // ===== Sending =====

    /**
     * Broadcasts a packet to every instance listening on its channel.
     *
     * @param packet the packet
     * @return a future that completes once the transport published it, and fails if the type is
     *         not registered, serialization failed, the transport is disconnected, or the backend
     *         refused the publication
     */
    public CompletableFuture<Void> send(Packet packet) {
        return publish(packet, null, null);
    }

    /**
     * Sends a packet to one instance.
     *
     * @param packet   the packet
     * @param targetId the target instance id
     * @return a future, as for {@link #send(Packet)}
     */
    public CompletableFuture<Void> sendTo(Packet packet, String targetId) {
        Objects.requireNonNull(targetId, "targetId");
        return publish(packet, targetId, null);
    }

    /**
     * Broadcasts a packet and waits for the first acknowledgment.
     *
     * @param packet    the packet
     * @param timeoutMs how long to wait for the acknowledgment
     * @return a future of the acknowledgment; fails as {@link #send(Packet)} does, or with a
     *         {@link TimeoutException} if no acknowledgment arrived in time
     */
    public CompletableFuture<AckResponse> sendWithAck(Packet packet, long timeoutMs) {
        return publishWithAck(packet, null, timeoutMs);
    }

    /**
     * Sends a packet to one instance and waits for its acknowledgment.
     *
     * @param packet    the packet
     * @param targetId  the target instance id
     * @param timeoutMs how long to wait for the acknowledgment
     * @return a future, as for {@link #sendWithAck(Packet, long)}
     */
    public CompletableFuture<AckResponse> sendWithAck(Packet packet, String targetId, long timeoutMs) {
        Objects.requireNonNull(targetId, "targetId");
        return publishWithAck(packet, targetId, timeoutMs);
    }

    private CompletableFuture<Void> publish(Packet packet, String targetId, String packetId) {
        Registration registration = packetRegistry.byClass(packet.getClass());
        if (registration == null) {
            metrics.recordSendFailure(null);
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Packet " + packet.getClass().getName() + " is not registered"));
        }
        String wire = registration.wireName();
        CompletableFuture<Void> published;
        try {
            if (!transport.isConnected()) {
                throw new IllegalStateException("Transport " + transport.getType() + " is not connected");
            }
            byte[] data = wrapPacket(packet, registration, targetId,
                    packetId != null ? packetId : UUID.randomUUID().toString(),
                    packetId != null ? ackChannel : null);
            published = targetId == null
                    ? transport.broadcast(registration.channel(), data)
                    : transport.sendTo(registration.channel(), targetId, data);
        } catch (Exception e) {
            published = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        published.whenComplete((v, error) -> {
            if (error == null) {
                metrics.recordSent(wire);
                result.complete(null);
            } else {
                Throwable cause = unwrap(error);
                metrics.recordSendFailure(wire);
                LOGGER.debug("Failed to send packet {} on {}", wire, registration.channel(), cause);
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    private CompletableFuture<AckResponse> publishWithAck(Packet packet, String targetId, long timeoutMs) {
        String packetId = UUID.randomUUID().toString();
        Registration registration = packetRegistry.byClass(packet.getClass());
        String wire = registration != null ? registration.wireName() : null;
        PendingAck pending = new PendingAck(new CompletableFuture<>(), targetId);
        pendingAcks.put(packetId, pending);
        CompletableFuture<AckResponse> future = pending.future;
        future.whenComplete((ack, error) -> {
            pendingAcks.remove(packetId);
            if (error instanceof TimeoutException) {
                metrics.recordAckTimeout(wire);
                LOGGER.debug("ACK for {} ({}) timed out after {} ms", wire, packetId, timeoutMs);
            }
        });
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        publish(packet, targetId, packetId).whenComplete((v, error) -> {
            if (error != null) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }

    // ===== Lifecycle =====

    /**
     * Connects the transport and subscribes the default channel, the reply channel and the
     * channel of every registered type. Returns once every subscription is active.
     *
     * @throws Exception if the connection or a subscription fails
     */
    public void start() throws Exception {
        List<CompletableFuture<Void>> subscriptions = new ArrayList<>();
        synchronized (lifecycleLock) {
            if (shutdown) {
                throw new IllegalStateException("Conduit was shut down");
            }
            if (started) {
                throw new IllegalStateException("Conduit already started");
            }
            transport.connect();
            subscriptions.add(subscribeChannel(defaultChannel));
            subscriptions.add(safely(() -> transport.subscribeUnicast(ackChannel, instanceId, unicastHandler)));
            for (Map.Entry<String, ChannelState> entry : channels.entrySet()) {
                entry.getValue().subscribed = subscribeChannel(entry.getKey());
                subscriptions.add(entry.getValue().subscribed);
            }
            started = true;
        }
        try {
            CompletableFuture.allOf(subscriptions.toArray(new CompletableFuture[0]))
                    .get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
        LOGGER.info("Conduit started on {} channel(s) with instanceId: {}", channels.size() + 1, instanceId);
    }

    /**
     * Shuts down: leaves the registry, fails the pending acknowledgments, closes the transport.
     */
    public void shutdown() {
        synchronized (lifecycleLock) {
            if (shutdown) {
                return;
            }
            shutdown = true;
            started = false;
        }
        LOGGER.info("Shutting down Conduit {}...", instanceId);
        if (registryKey != null) {
            synchronized (Conduit.class) {
                instances.remove(registryKey, this);
                if (defaultInstance == this) {
                    defaultInstance = null;
                }
            }
        }
        IllegalStateException closed = new IllegalStateException("Conduit shut down");
        pendingAcks.values().forEach(p -> p.future.completeExceptionally(closed));
        pendingAcks.clear();
        try {
            transport.close();
        } catch (Exception e) {
            LOGGER.error("Error while closing transport {}", transport.getType(), e);
        }
        LOGGER.info("Conduit shutdown complete");
    }

    /**
     * Shuts down every registered instance and clears the registry. Package-private: for tests.
     */
    static void resetAll() {
        for (Conduit conduit : List.copyOf(instances.values())) {
            conduit.shutdown();
        }
        synchronized (Conduit.class) {
            instances.clear();
            defaultInstance = null;
        }
    }

    // ===== Accessors =====

    /** @return this instance id, the unicast address of this instance */
    public String instanceId() { return instanceId; }

    /** @return the default channel */
    public String defaultChannel() { return defaultChannel; }

    /** @return the transport */
    public Transport transport() { return transport; }

    /** @return the counters of this instance */
    public ConduitMetrics metrics() { return metrics; }

    /** @return whether {@link #start()} completed and {@link #shutdown()} was not called */
    public boolean isStarted() { return started; }

    // ===== Reception =====

    private void handleIncoming(String channel, byte[] data, boolean broadcast) {
        try {
            receive(channel, data, broadcast);
        } catch (RuntimeException e) {
            // Never let anything climb back into the transport's delivery thread.
            LOGGER.error("Unexpected failure while handling a packet on {}", channel, e);
        }
    }

    private void receive(String channel, byte[] data, boolean broadcast) {
        PacketEnvelope envelope;
        try {
            envelope = PacketEnvelope.decode(data);
        } catch (PacketEnvelope.MalformedEnvelopeException e) {
            refuse(null, "malformed envelope on " + channel + ": " + e.getMessage());
            return;
        }
        if (broadcast && instanceId.equals(envelope.senderId())) {
            return; // Our own broadcast, echoed back by the transport.
        }

        // Routing: the envelope must have been built for exactly where it arrived. Checked even
        // unsigned (cheap); with a verifier, it is what stops a signed packet replayed elsewhere.
        if (!envelope.namespace().equals(transport.channelPrefix()) || !envelope.channel().equals(channel)) {
            refuse(envelope, "built for channel '" + envelope.namespace() + envelope.channel()
                    + "', received on '" + transport.channelPrefix() + channel + "'");
            return;
        }
        if (broadcast ? envelope.target() != null : !instanceId.equals(envelope.target())) {
            refuse(envelope, "built for target " + envelope.target() + ", received "
                    + (broadcast ? "as a broadcast" : "by " + instanceId));
            return;
        }
        if (envelope.isAckResponse() != (!broadcast && channel.equals(ackChannel))) {
            refuse(envelope, envelope.isAckResponse() ? "ACK response off the reply channel" : "packet on the reply channel");
            return;
        }

        if (verifier != null && !authenticate(envelope)) {
            return;
        }

        if (envelope.isAckResponse()) {
            completeAck(envelope);
            return;
        }

        String wire = envelope.wireName();
        Registration registration = packetRegistry.byWire(wire);
        if (registration == null) {
            metrics.recordUnknownType();
            if (loggedUnknownTypes.size() < LOG_KEYS_CAP && loggedUnknownTypes.add(wire)) {
                LOGGER.debug("Unknown packet type '{}' on {} (logged once)", wire, channel);
            }
            sendAck(envelope, AckResponse.failure(envelope.packetId(), "Unknown packet type: " + wire));
            return;
        }

        Packet packet;
        try {
            packet = serializer.deserialize(compressor.decompress(envelope.payloadUnsafe()), registration.packetClass());
            if (packet == null) {
                throw new IllegalArgumentException("empty payload");
            }
        } catch (Exception e) {
            metrics.recordRefused(wire);
            LOGGER.warn("Cannot decode packet '{}' from {}", wire, envelope.senderId(), e);
            sendAck(envelope, AckResponse.failure(envelope.packetId(), "Cannot decode packet: " + e.getMessage()));
            return;
        }
        metrics.recordReceived(wire);
        dispatch(envelope, packet, wire);
    }

    private boolean authenticate(PacketEnvelope envelope) {
        if (!envelope.isSigned()) {
            refuse(envelope, "unsigned envelope");
            return false;
        }
        Verdict verdict;
        try {
            verdict = verifier.verify(envelope.keyId(), envelope.signedData(), envelope.signature(), envelope);
        } catch (RuntimeException e) {
            LOGGER.error("Envelope verifier threw for {}", envelope, e);
            verdict = Verdict.refuse("verifier threw " + e);
        }
        if (verdict instanceof Verdict.Refused refused) {
            refuse(envelope, refused.reason());
            return false;
        }
        if (verdict == null) {
            refuse(envelope, "verifier returned no verdict");
            return false;
        }
        long now = clock.millis();
        long skew = Math.abs(now - envelope.timestampMillis());
        if (skew > maxClockSkew.toMillis()) {
            refuse(envelope, "timestamp off by " + skew + " ms (max " + maxClockSkew.toMillis() + " ms)");
            return false;
        }
        if (!replayGuard.firstSeen(envelope.keyId() + '/' + envelope.packetId(),
                envelope.timestampMillis() + maxClockSkew.toMillis(), now)) {
            refuse(envelope, "replayed packet id " + envelope.packetId());
            return false;
        }
        return true;
    }

    private void dispatch(PacketEnvelope envelope, Packet packet, String wire) {
        Consumer<AckResponse> ackCallback = envelope.requiresAck() ? ack -> sendAck(envelope, ack) : null;
        Runnable task = () -> {
            CompletableFuture<Boolean> handled;
            try {
                handled = handlerRegistry.dispatch(packet, ackCallback);
            } catch (RuntimeException e) {
                handled = CompletableFuture.failedFuture(e);
            }
            handled.whenComplete((found, error) -> {
                if (error != null) {
                    Throwable cause = unwrap(error);
                    metrics.recordHandlerFailure(wire);
                    LOGGER.warn("Handler for packet '{}' failed", wire, cause);
                    sendAck(envelope, AckResponse.failure(envelope.packetId(), String.valueOf(cause.getMessage())));
                } else if (!found) {
                    LOGGER.debug("No handler for packet '{}'", wire);
                    sendAck(envelope, AckResponse.failure(envelope.packetId(), "No handler registered"));
                }
            });
        };
        try {
            handlerExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            metrics.recordHandlerFailure(wire);
            LOGGER.warn("Handler executor refused packet '{}' from {}: dropped", wire, envelope.senderId(), e);
            sendAck(envelope, AckResponse.failure(envelope.packetId(), "Handler executor refused the packet"));
        }
    }

    private void completeAck(PacketEnvelope envelope) {
        PendingAck pending = pendingAcks.get(envelope.ackId());
        if (pending == null) {
            LOGGER.trace("ACK {} matches no pending request (late, or another receiver answered first)", envelope.ackId());
            return;
        }
        if (pending.targetId != null && !pending.targetId.equals(envelope.senderId())) {
            refuse(envelope, "ACK from " + envelope.senderId() + " for a request sent to " + pending.targetId);
            return;
        }
        if (pendingAcks.remove(envelope.ackId(), pending)) {
            pending.future.complete(envelope.ackResponseBody());
        }
    }

    /** Sends an ACK response if the envelope asked for one; never throws. */
    private void sendAck(PacketEnvelope request, AckResponse response) {
        if (!request.requiresAck()) {
            return;
        }
        try {
            PacketEnvelope ack = PacketEnvelope.ackResponse(UUID.randomUUID().toString(), clock.millis(), instanceId,
                    transport.channelPrefix(), request.replyChannel(), request.senderId(), request.packetId(),
                    signer != null ? signer.keyId() : null, response);
            transport.sendTo(request.replyChannel(), request.senderId(), ack.encode(signer))
                    .whenComplete((v, error) -> {
                        if (error != null) {
                            LOGGER.warn("Failed to send ACK for {} to {}", request.packetId(), request.senderId(), unwrap(error));
                        }
                    });
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to send ACK for {} to {}", request.packetId(), request.senderId(), e);
        }
    }

    private void refuse(PacketEnvelope envelope, String reason) {
        metrics.recordRefused(envelope != null ? envelope.wireName() : null);
        String sender = envelope != null ? envelope.senderId() : "?";
        long now = System.currentTimeMillis();
        if (lastRefusalLog.size() >= LOG_KEYS_CAP) {
            lastRefusalLog.clear(); // Senders are attacker-controlled: never let this map grow without bound.
        }
        Long previous = lastRefusalLog.get(sender);
        if (previous == null || now - previous >= REFUSAL_LOG_INTERVAL_MS) {
            lastRefusalLog.put(sender, now);
            LOGGER.warn("Refused envelope from {}: {} ({}; further refusals from this sender muted for a minute)",
                    sender, reason, envelope);
        }
    }

    // ===== Helpers =====

    private byte[] wrapPacket(Packet packet, Registration registration, String targetId, String packetId,
                              String replyChannel) throws Exception {
        byte[] payload = compressor.compress(serializer.serialize(packet));
        PacketEnvelope envelope = PacketEnvelope.packet(packetId, clock.millis(), instanceId,
                transport.channelPrefix(), registration.channel(), targetId, registration.wireName(),
                replyChannel, signer != null ? signer.keyId() : null, payload);
        return envelope.encode(signer);
    }

    private CompletableFuture<Void> subscribeChannel(String channel) {
        CompletableFuture<Void> broadcast = safely(() -> transport.subscribe(channel, broadcastHandler));
        CompletableFuture<Void> unicast = safely(() -> transport.subscribeUnicast(channel, instanceId, unicastHandler));
        CompletableFuture<Void> both = CompletableFuture.allOf(broadcast, unicast);
        both.whenComplete((v, error) -> {
            if (error != null) {
                LOGGER.error("Failed to subscribe channel {}", channel, unwrap(error));
            }
        });
        return both;
    }

    private CompletableFuture<Void> unsubscribeChannel(String channel) {
        CompletableFuture<Void> both = CompletableFuture.allOf(
                safely(() -> transport.unsubscribe(channel)),
                safely(() -> transport.unsubscribeUnicast(channel, instanceId)));
        both.whenComplete((v, error) -> {
            if (error != null) {
                LOGGER.error("Failed to unsubscribe channel {}", channel, unwrap(error));
            }
        });
        return both;
    }

    private String channelOf(Class<? extends Packet> packetClass) {
        PacketMeta meta = packetClass.getAnnotation(PacketMeta.class);
        return meta != null && !meta.channel().isEmpty() ? meta.channel() : defaultChannel;
    }

    private static CompletableFuture<Void> safely(java.util.function.Supplier<CompletableFuture<Void>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private static final class ChannelState {
        int refs;
        CompletableFuture<Void> subscribed = CompletableFuture.completedFuture(null);
    }

    private record PendingAck(CompletableFuture<AckResponse> future, String targetId) {}

    /**
     * Remembers the ids of accepted envelopes for as long as their timestamp is within the clock
     * skew: past that, the timestamp check refuses them anyway.
     */
    private static final class ReplayGuard {
        private final Map<String, Long> seen = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Map.Entry<String, Long>> expiries = new ConcurrentLinkedQueue<>();

        boolean firstSeen(String key, long expiresAt, long now) {
            Map.Entry<String, Long> head;
            while ((head = expiries.peek()) != null && head.getValue() < now) {
                if (expiries.remove(head)) {
                    seen.remove(head.getKey(), head.getValue());
                }
            }
            if (seen.putIfAbsent(key, expiresAt) != null) {
                return false;
            }
            expiries.add(Map.entry(key, expiresAt));
            return true;
        }
    }

    // ===== Builder =====

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static ConduitBuilder builder() {
        return new ConduitBuilder();
    }

    /**
     * Builder of {@link Conduit}.
     */
    public static class ConduitBuilder {

        private Transport transport;
        private Serializer serializer = new JsonSerializer();
        private Compressor compressor = new NoOpCompressor();
        private String defaultChannel = "conduit:packets";
        private String instanceId = UUID.randomUUID().toString();
        private String name;
        private boolean unregistered;
        private boolean requireWireNames;
        private Executor handlerExecutor = Runnable::run;
        private EnvelopeSigner signer;
        private EnvelopeVerifier verifier;
        private Duration maxClockSkew = Duration.ofSeconds(30);
        private Clock clock = Clock.systemUTC();

        private ConduitBuilder() {
        }

        /**
         * The transport. Required.
         *
         * @param transport the transport
         * @return this builder
         */
        public ConduitBuilder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * The serializer. Default: {@link JsonSerializer}.
         *
         * @param serializer the serializer
         * @return this builder
         */
        public ConduitBuilder serializer(Serializer serializer) {
            this.serializer = serializer;
            return this;
        }

        /**
         * The compressor. Default: {@link NoOpCompressor}.
         *
         * @param compressor the compressor
         * @return this builder
         */
        public ConduitBuilder compressor(Compressor compressor) {
            this.compressor = compressor;
            return this;
        }

        /**
         * The channel of packets without a {@link PacketMeta#channel()}. Its {@code :ack} suffix is
         * this instance's reply channel. Default: {@code conduit:packets}.
         *
         * @param defaultChannel the default channel
         * @return this builder
         */
        public ConduitBuilder defaultChannel(String defaultChannel) {
            this.defaultChannel = defaultChannel;
            return this;
        }

        /**
         * The unique id of this instance, its unicast address. Default: a random UUID.
         *
         * @param instanceId the instance id
         * @return this builder
         */
        public ConduitBuilder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        /**
         * The name of this instance in the registry ({@link Conduit#getInstance(String)}).
         * Default: the instance id.
         *
         * @param name the registry name
         * @return this builder
         */
        public ConduitBuilder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Keeps this instance out of the static registry: it is never returned by
         * {@link Conduit#getInstance()} nor {@link Conduit#getInstance(String)}, so the default
         * methods of {@link Packet} do not reach it. For several libraries embedding Conduit in
         * one JVM, each with its own instance.
         *
         * @return this builder
         */
        public ConduitBuilder unregistered() {
            this.unregistered = true;
            return this;
        }

        /**
         * Refuses to register a packet type without an explicit wire name
         * ({@link PacketMeta#wire()} or {@link Conduit#registerPacket(String, Class)}).
         *
         * @return this builder
         */
        public ConduitBuilder requireWireNames() {
            this.requireWireNames = true;
            return this;
        }

        /**
         * The executor every handler is called on. Decoding stays on the transport thread. Order of
         * reception on a channel is kept when the executor runs tasks one at a time in submission
         * order. A refused task drops the packet (logged, counted, NACKed if an ACK was required).
         * Default: the transport thread.
         *
         * @param handlerExecutor the executor
         * @return this builder
         */
        public ConduitBuilder handlerExecutor(Executor handlerExecutor) {
            this.handlerExecutor = handlerExecutor;
            return this;
        }

        /**
         * Signs every outgoing envelope, ACK responses included.
         *
         * @param signer the signer
         * @return this builder
         */
        public ConduitBuilder signer(EnvelopeSigner signer) {
            this.signer = signer;
            return this;
        }

        /**
         * Verifies every incoming envelope, ACK responses included. Once set, an envelope is
         * refused (dropped, counted, never answered) if it is unsigned, refused by the verifier,
         * dated outside {@link #maxClockSkew(Duration)}, or a replay of one already accepted.
         *
         * @param verifier the verifier
         * @return this builder
         */
        public ConduitBuilder verifier(EnvelopeVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        /**
         * How far an envelope's timestamp may be from this instance's clock, and how long its id
         * is remembered against replay. Only applies with a {@link #verifier(EnvelopeVerifier)}.
         * Default: 30 seconds.
         *
         * @param maxClockSkew the tolerance
         * @return this builder
         */
        public ConduitBuilder maxClockSkew(Duration maxClockSkew) {
            this.maxClockSkew = maxClockSkew;
            return this;
        }

        /**
         * The clock that dates envelopes and checks their timestamps. Default: system UTC.
         *
         * @param clock the clock
         * @return this builder
         */
        public ConduitBuilder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Builds the instance and, unless {@link #unregistered()}, adds it to the registry (as the
         * default instance if there is none yet).
         *
         * @return the instance
         * @throws IllegalStateException if the transport is missing, or the registry name is taken
         */
        public Conduit build() {
            if (transport == null) {
                throw new IllegalStateException("Transport must be set");
            }
            Objects.requireNonNull(serializer, "serializer");
            Objects.requireNonNull(compressor, "compressor");
            Objects.requireNonNull(defaultChannel, "defaultChannel");
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(handlerExecutor, "handlerExecutor");
            Objects.requireNonNull(clock, "clock");
            if (maxClockSkew == null || maxClockSkew.isNegative() || maxClockSkew.isZero()) {
                throw new IllegalArgumentException("maxClockSkew must be positive");
            }
            if (unregistered) {
                return new Conduit(this, null);
            }
            String registryKey = name != null ? name : instanceId;
            Conduit conduit = new Conduit(this, registryKey);
            synchronized (Conduit.class) {
                if (instances.putIfAbsent(registryKey, conduit) != null) {
                    throw new IllegalStateException("A Conduit instance named '" + registryKey + "' already exists");
                }
                if (defaultInstance == null) {
                    defaultInstance = conduit;
                }
            }
            return conduit;
        }
    }
}
