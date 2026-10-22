package fr.traqueur.conduit.core;

import fr.traqueur.conduit.compression.GzipCompressor;
import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.packet.TargetableAcknowledgeablePacket;
import fr.traqueur.conduit.packet.TargetablePacket;
import fr.traqueur.conduit.security.EnvelopeVerifier.Verdict;
import fr.traqueur.conduit.security.HmacSigner;
import fr.traqueur.conduit.security.HmacVerifier;
import fr.traqueur.conduit.transport.Transport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Behaviour every transport must have, run on each of them: in-memory, Redis, RabbitMQ.
 *
 * <p>Subclasses only say how to make a transport on their shared backend. Every instance built
 * here talks through a fresh transport on that same backend.</p>
 */
public abstract class BaseConduitIntegrationTest {

    protected Conduit conduit1;
    protected Conduit conduit2;
    private final List<Conduit> extra = new ArrayList<>();
    private final List<Transport> rawTransports = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    // Test packets
    public record ChatMessagePacket(UUID senderId, String message) implements Packet {}

    @PacketMeta(channel = "player-events")
    public record PlayerJoinPacket(UUID playerId, String playerName) implements Packet {}

    public record PlayerKickPacket(UUID playerId, String reason) implements TargetablePacket {}

    public record ConfigReloadPacket(String config) implements AcknowledgeablePacket {}

    public record DataSyncPacket(String data) implements TargetableAcknowledgeablePacket {}

    @PacketMeta(channel = "late", wire = "test.late.v1")
    public record LatePacket(String value) implements AcknowledgeablePacket {}

    @PacketMeta(channel = "shared", wire = "test.shared.a.v1")
    public record SharedA(String value) implements AcknowledgeablePacket {}

    @PacketMeta(channel = "shared", wire = "test.shared.b.v1")
    public record SharedB(String value) implements Packet {}

    @PacketMeta(wire = "test.rotation.v1")
    public record RotationV1(String shopId) implements Packet {}

    @PacketMeta(wire = "test.rotation.v1")
    public record RenamedRotation(String shopId) implements Packet {}

    @PacketMeta(channel = "secure", wire = "test.secure.v1")
    public record SecurePacket(String value) implements AcknowledgeablePacket {}

    @PacketMeta(channel = "secure-other", wire = "test.secure.other.v1")
    public record OtherSecurePacket(String value) implements Packet {}

    public record SequencePacket(int n) implements Packet {}

    /**
     * @return a new, unconnected transport on the backend shared by the whole test
     */
    protected abstract Transport newTransport() throws Exception;

    /**
     * @return how long to wait before asserting that something did NOT arrive
     */
    protected Duration settle() {
        return Duration.ofMillis(500);
    }

    protected Conduit.ConduitBuilder builder(String instanceId) throws Exception {
        return Conduit.builder()
                .transport(newTransport())
                .instanceId(instanceId)
                .defaultChannel("conduit-test");
    }

    /** Builds, registers the standard packets, starts, and tears down after the test. */
    protected Conduit started(Conduit.ConduitBuilder builder) throws Exception {
        Conduit conduit = builder.build();
        extra.add(conduit);
        registerPackets(conduit);
        conduit.start();
        return conduit;
    }

    @BeforeEach
    void setUp() throws Exception {
        conduit1 = builder("instance-1").build();
        conduit2 = builder("instance-2").build();
        registerPackets(conduit1);
        registerPackets(conduit2);
        conduit1.start();
        conduit2.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conduit1 != null) conduit1.shutdown();
        if (conduit2 != null) conduit2.shutdown();
        extra.forEach(Conduit::shutdown);
        for (Transport raw : rawTransports) {
            raw.close();
        }
        executors.forEach(ExecutorService::shutdownNow);
        Conduit.resetAll();
    }

    private void registerPackets(Conduit conduit) {
        conduit.registerPacket(ChatMessagePacket.class);
        conduit.registerPacket(PlayerJoinPacket.class);
        conduit.registerPacket(PlayerKickPacket.class);
        conduit.registerPacket(ConfigReloadPacket.class);
        conduit.registerPacket(DataSyncPacket.class);
    }

    private void sleepSettle() throws InterruptedException {
        Thread.sleep(settle().toMillis());
    }

    private ExecutorService singleThread(String name) {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, name));
        executors.add(executor);
        return executor;
    }

    /** A bare transport on the same backend, to capture and re-publish raw bytes like an attacker would. */
    private Transport rawTransport() throws Exception {
        Transport raw = newTransport();
        raw.connect();
        rawTransports.add(raw);
        return raw;
    }

    // ===== 1.3.0 behaviour =====

    @Test
    @DisplayName("Should send and receive simple broadcast packet")
    void shouldSendAndReceiveBroadcastPacket() throws Exception {
        UUID senderId = UUID.randomUUID();
        AtomicReference<ChatMessagePacket> received = new AtomicReference<>();
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> {
            received.set(packet);
            return null;
        });

        conduit1.send(new ChatMessagePacket(senderId, "Hello World")).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(received.get().senderId()).isEqualTo(senderId);
        assertThat(received.get().message()).isEqualTo("Hello World");
    }

    @Test
    @DisplayName("Should respect @PacketMeta channel annotation")
    void shouldRespectPacketMetaChannel() throws Exception {
        UUID playerId = UUID.randomUUID();
        AtomicReference<PlayerJoinPacket> received = new AtomicReference<>();
        conduit2.registerHandler(PlayerJoinPacket.class, (packet, ack) -> {
            received.set(packet);
            return null;
        });

        conduit1.send(new PlayerJoinPacket(playerId, "TestPlayer"));

        await().atMost(10, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(received.get().playerId()).isEqualTo(playerId);
    }

    @Test
    @DisplayName("Should send unicast packet to specific target only")
    void shouldSendUnicastPacket() throws Exception {
        AtomicInteger got1 = new AtomicInteger();
        AtomicInteger got2 = new AtomicInteger();
        conduit1.registerHandler(PlayerKickPacket.class, (packet, ack) -> { got1.incrementAndGet(); return null; });
        conduit2.registerHandler(PlayerKickPacket.class, (packet, ack) -> { got2.incrementAndGet(); return null; });

        conduit1.sendTo(new PlayerKickPacket(UUID.randomUUID(), "Cheating"), "instance-2");

        await().atMost(10, TimeUnit.SECONDS).until(() -> got2.get() == 1);
        sleepSettle();
        assertThat(got1.get()).isZero();
    }

    @Test
    @DisplayName("Should send packet with ACK and receive acknowledgment")
    void shouldSendPacketWithAck() throws Exception {
        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ack) -> {
            ack.accept(AckResponse.success("config-123", "Config reloaded successfully"));
            return null;
        });

        AckResponse ack = conduit1.sendWithAck(new ConfigReloadPacket("server.properties"), 15000L)
                .get(15, TimeUnit.SECONDS);

        assertThat(ack.success()).isTrue();
        assertThat(ack.message()).isEqualTo("Config reloaded successfully");
    }

    @Test
    @DisplayName("Should send unicast packet with ACK to specific target")
    void shouldSendUnicastPacketWithAck() throws Exception {
        conduit2.registerHandler(DataSyncPacket.class, (packet, ack) -> {
            ack.accept(AckResponse.success("sync-456", "Data synced: " + packet.data()));
            return null;
        });

        AckResponse ack = conduit1.sendWithAck(new DataSyncPacket("important-data"), "instance-2", 15000L)
                .get(15, TimeUnit.SECONDS);

        assertThat(ack.success()).isTrue();
        assertThat(ack.message()).contains("important-data");
    }

    @Test
    @DisplayName("Should receive error ACK when no handler is registered")
    void shouldReceiveErrorAckWhenNoHandler() throws Exception {
        AckResponse ack = conduit1.sendWithAck(new ConfigReloadPacket("test"), 10000L).get(15, TimeUnit.SECONDS);

        assertThat(ack.success()).isFalse();
        assertThat(ack.message()).contains("No handler registered");
    }

    @Test
    @DisplayName("Broadcast reaches every other instance, never the sender")
    void shouldHandleMultipleBroadcastReceivers() throws Exception {
        Conduit conduit3 = started(builder("instance-3"));
        AtomicInteger got1 = new AtomicInteger();
        AtomicInteger got2 = new AtomicInteger();
        AtomicInteger got3 = new AtomicInteger();
        conduit1.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got1.incrementAndGet(); return null; });
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got2.incrementAndGet(); return null; });
        conduit3.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got3.incrementAndGet(); return null; });

        conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "Broadcast message"));

        await().atMost(10, TimeUnit.SECONDS).until(() -> got2.get() == 1 && got3.get() == 1);
        sleepSettle();
        assertThat(got1.get()).isZero();
    }

    @Test
    @DisplayName("Should handle packet with compression (Gzip)")
    void shouldHandlePacketWithCompression() throws Exception {
        Conduit a = started(builder("gzip-1").compressor(new GzipCompressor()));
        Conduit b = started(builder("gzip-2").compressor(new GzipCompressor()));
        AtomicReference<ChatMessagePacket> received = new AtomicReference<>();
        b.registerHandler(ChatMessagePacket.class, (packet, ack) -> { received.set(packet); return null; });

        String largeMessage = "A".repeat(1000);
        a.send(new ChatMessagePacket(UUID.randomUUID(), largeMessage)).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(received.get().message()).isEqualTo(largeMessage);
    }

    @Test
    @DisplayName("Should handle packet with async handler on different thread")
    void shouldHandlePacketWithAsyncHandler() throws Exception {
        ExecutorService executor = singleThread("async-handler");
        AtomicReference<String> handlerThread = new AtomicReference<>();
        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ack) ->
                CompletableFuture.runAsync(() -> {
                    handlerThread.set(Thread.currentThread().getName());
                    ack.accept(AckResponse.success("async-test", "Handled async"));
                }, executor));

        AckResponse ack = conduit1.sendWithAck(new ConfigReloadPacket("async-config"), 15000L).get(15, TimeUnit.SECONDS);

        assertThat(handlerThread.get()).isEqualTo("async-handler");
        assertThat(ack.success()).isTrue();
        assertThat(ack.message()).isEqualTo("Handled async");
    }

    // ===== 1. Wire names =====

    @Test
    @DisplayName("Two classes under one wire name are refused, naming both")
    void wireNameCollisionIsRefused() {
        conduit1.registerPacket(RotationV1.class);

        assertThatThrownBy(() -> conduit1.registerPacket(RenamedRotation.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RotationV1.class.getName())
                .hasMessageContaining(RenamedRotation.class.getName())
                .hasMessageContaining("test.rotation.v1");
        assertThatThrownBy(() -> conduit1.registerPacket("ChatMessagePacket", SharedB.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ChatMessagePacket.class.getName());
        assertThatThrownBy(() -> conduit1.registerPacket("another.name", RotationV1.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test.rotation.v1");

        // Same class, same name: nothing happens.
        conduit1.registerPacket(RotationV1.class).join();
        conduit1.registerPacket("test.rotation.v1", RotationV1.class).join();
    }

    @Test
    @DisplayName("The wire name, not the class name, finds the type: a renamed class still talks")
    void wireNameSurvivesRename() throws Exception {
        conduit1.registerPacket(RotationV1.class);
        conduit2.registerPacket(RenamedRotation.class);
        AtomicReference<RenamedRotation> received = new AtomicReference<>();
        conduit2.registerHandler(RenamedRotation.class, (packet, ack) -> { received.set(packet); return null; });

        conduit1.send(new RotationV1("shop-1")).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() -> received.get() != null);
        assertThat(received.get().shopId()).isEqualTo("shop-1");
        assertThat(conduit2.metrics().byWire().get("test.rotation.v1").received()).isEqualTo(1);
    }

    @Test
    @DisplayName("requireWireNames refuses a type without an explicit wire name")
    void requireWireNamesRefusesImplicitNames() throws Exception {
        Conduit strict = builder("strict").requireWireNames().unregistered().build();
        extra.add(strict);

        assertThatThrownBy(() -> strict.registerPacket(ChatMessagePacket.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ChatMessagePacket.class.getName());
        strict.registerPacket(RotationV1.class).join();
        strict.registerPacket("test.chat.v1", ChatMessagePacket.class).join();
    }

    // ===== 2. Hot registration =====

    @Test
    @DisplayName("A packet registered after start() on a custom channel is received")
    void packetRegisteredAfterStartIsReceived() throws Exception {
        AtomicReference<LatePacket> received = new AtomicReference<>();
        conduit2.registerPacket(LatePacket.class).get(10, TimeUnit.SECONDS);
        conduit2.registerHandler(LatePacket.class, (packet, ack) -> {
            received.set(packet);
            ack.accept(AckResponse.success("late"));
            return null;
        });
        conduit1.registerPacket(LatePacket.class).get(10, TimeUnit.SECONDS);

        AckResponse ack = conduit1.sendWithAck(new LatePacket("hello"), 10_000).get(15, TimeUnit.SECONDS);

        assertThat(ack.success()).isTrue();
        assertThat(received.get().value()).isEqualTo("hello");
    }

    @Test
    @DisplayName("A channel shared by two types stays subscribed until the last one leaves")
    void sharedChannelIsReferenceCounted() throws Exception {
        for (Conduit c : List.of(conduit1, conduit2)) {
            c.registerPacket(SharedA.class).get(10, TimeUnit.SECONDS);
            c.registerPacket(SharedB.class).get(10, TimeUnit.SECONDS);
        }
        AtomicInteger gotB = new AtomicInteger();
        conduit2.registerHandler(SharedA.class, (packet, ack) -> { ack.accept(AckResponse.success("a")); return null; });
        conduit2.registerHandler(SharedB.class, (packet, ack) -> { gotB.incrementAndGet(); return null; });

        // Remove A: B, on the same channel, still arrives; A is now an unknown type, NACKed.
        conduit2.unregisterPacket(SharedA.class).get(10, TimeUnit.SECONDS);
        conduit1.send(new SharedB("still here"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> gotB.get() == 1);

        AckResponse nack = conduit1.sendWithAck(new SharedA("gone"), 10_000).get(15, TimeUnit.SECONDS);
        assertThat(nack.success()).isFalse();
        assertThat(nack.message()).contains("Unknown packet type").contains("test.shared.a.v1");
        assertThat(conduit2.metrics().unknownType()).isEqualTo(1);

        // Remove B too: the channel is unsubscribed, nothing arrives any more.
        conduit2.unregisterPacket(SharedB.class).get(10, TimeUnit.SECONDS);
        long seenBefore = conduit2.metrics().received() + conduit2.metrics().unknownType();
        conduit1.send(new SharedB("nobody listens")).get(10, TimeUnit.SECONDS);
        sleepSettle();
        assertThat(conduit2.metrics().received() + conduit2.metrics().unknownType()).isEqualTo(seenBefore);
        assertThat(gotB.get()).isEqualTo(1);

        // And it comes back when a type uses it again.
        conduit2.registerPacket(SharedB.class).get(10, TimeUnit.SECONDS);
        conduit2.registerHandler(SharedB.class, (packet, ack) -> { gotB.incrementAndGet(); return null; });
        conduit1.send(new SharedB("back"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> gotB.get() == 2);
    }

    @Test
    @DisplayName("Removing a type from the default channel keeps the default channel")
    void defaultChannelIsNeverUnsubscribed() throws Exception {
        AtomicInteger got = new AtomicInteger();
        conduit2.unregisterPacket(ChatMessagePacket.class).get(10, TimeUnit.SECONDS);
        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ack) -> { got.incrementAndGet(); ack.accept(AckResponse.success("x")); return null; });

        assertThat(conduit1.sendWithAck(new ConfigReloadPacket("x"), 10_000).get(15, TimeUnit.SECONDS).success()).isTrue();
        assertThat(got.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Registering, removing and receiving concurrently")
    void concurrentRegistrationAndReception() throws Exception {
        AtomicInteger got = new AtomicInteger();
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got.incrementAndGet(); return null; });
        ExecutorService churn = Executors.newFixedThreadPool(4);
        executors.add(churn);
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 50; j++) {
                    conduit2.registerPacket(SharedB.class).join();
                    conduit2.unregisterPacket(SharedB.class).join();
                }
            }, churn));
        }
        for (int i = 0; i < 100; i++) {
            conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "m" + i));
        }
        CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        await().atMost(15, TimeUnit.SECONDS).until(() -> got.get() == 100);
    }

    // ===== 3. Sends that report =====

    @Test
    @DisplayName("Sending on a disconnected transport fails the future")
    void sendOnDisconnectedTransportFails() throws Exception {
        Conduit notStarted = builder("not-started").unregistered().build();
        extra.add(notStarted);
        notStarted.registerPacket(ChatMessagePacket.class);
        notStarted.registerPacket(ConfigReloadPacket.class);

        assertThatThrownBy(() -> notStarted.send(new ChatMessagePacket(UUID.randomUUID(), "x")).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notStarted.sendWithAck(new ConfigReloadPacket("x"), 5000).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(notStarted.metrics().sendFailures()).isEqualTo(2);

        conduit1.shutdown();
        assertThatThrownBy(() -> conduit1.sendTo(new PlayerKickPacket(UUID.randomUUID(), "x"), "instance-2").get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("Sending an unregistered type or an unserializable packet fails the future")
    void sendFailuresAreReported() throws Exception {
        assertThatThrownBy(() -> conduit1.send(new SharedB("unregistered")).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalArgumentException.class);

        Conduit broken = started(builder("broken").serializer(new fr.traqueur.conduit.serialization.Serializer() {
            @Override public byte[] serialize(Packet packet) { throw new IllegalStateException("cannot serialize"); }
            @Override public <T extends Packet> T deserialize(byte[] data, Class<T> c) { throw new IllegalStateException(); }
            @Override public String getType() { return "broken"; }
        }));
        assertThatThrownBy(() -> broken.send(new ChatMessagePacket(UUID.randomUUID(), "x")).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("cannot serialize");
        assertThat(broken.metrics().byWire().get("ChatMessagePacket").sendFailures()).isEqualTo(1);
    }

    // ===== 4. Handler executor =====

    @Test
    @DisplayName("Handlers run on the configured executor, in order of reception")
    void handlersRunOnExecutorInOrder() throws Exception {
        Conduit sender = started(builder("seq-sender"));
        Conduit receiver = started(builder("seq-receiver").handlerExecutor(singleThread("main-thread")));
        sender.registerPacket(SequencePacket.class);
        receiver.registerPacket(SequencePacket.class);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = new CopyOnWriteArrayList<>();
        receiver.registerHandler(SequencePacket.class, (packet, ack) -> {
            order.add(packet.n());
            threads.add(Thread.currentThread().getName());
            return null;
        });

        for (int i = 0; i < 200; i++) {
            sender.send(new SequencePacket(i));
        }

        await().atMost(15, TimeUnit.SECONDS).until(() -> order.size() == 200);
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) expected.add(i);
        assertThat(order).containsExactlyElementsOf(expected);
        assertThat(threads).containsOnly("main-thread");
    }

    @Test
    @DisplayName("A handler that throws on the executor is counted and NACKed")
    void handlerThrowingOnExecutorIsNacked() throws Exception {
        Conduit receiver = started(builder("throwing").handlerExecutor(singleThread("handler-thread")));
        receiver.registerHandler(ConfigReloadPacket.class, (packet, ack) -> { throw new IllegalStateException("boom"); });

        // Unicast, so that only the throwing instance answers.
        AckResponse ack = conduit1.sendWithAck(new ConfigReloadPacket("x"), "throwing", 10_000).get(15, TimeUnit.SECONDS);

        assertThat(ack.success()).isFalse();
        assertThat(ack.message()).isEqualTo("boom");
        assertThat(receiver.metrics().handlerFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("A closed executor drops the packet, NACKs it, and nothing reaches the transport")
    void closedExecutorDropsAndNacks() throws Exception {
        ExecutorService closed = singleThread("closed");
        closed.shutdown();
        Conduit receiver = started(builder("closed-executor").handlerExecutor(closed));
        AtomicInteger calls = new AtomicInteger();
        receiver.registerHandler(DataSyncPacket.class, (packet, ack) -> { calls.incrementAndGet(); return null; });

        AckResponse ack = conduit1.sendWithAck(new DataSyncPacket("x"), "closed-executor", 10_000).get(15, TimeUnit.SECONDS);

        assertThat(ack.success()).isFalse();
        assertThat(ack.message()).contains("refused");
        assertThat(calls.get()).isZero();
        assertThat(receiver.metrics().handlerFailures()).isEqualTo(1);
        // The transport survived: the next packet still arrives on another instance.
        AtomicInteger later = new AtomicInteger();
        conduit2.registerHandler(ChatMessagePacket.class, (packet, a) -> { later.incrementAndGet(); return null; });
        receiver.send(new ChatMessagePacket(UUID.randomUUID(), "still alive"));
        await().atMost(10, TimeUnit.SECONDS).until(() -> later.get() == 1);
    }

    // ===== 6. Signed envelopes =====

    private static final byte[] SECRET_A = "secret-of-server-a-32-bytes-long".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECRET_B = "secret-of-server-b-32-bytes-long".getBytes(StandardCharsets.UTF_8);
    private static final HmacVerifier KEYS = HmacVerifier.sha256(Map.of("server-a", SECRET_A, "server-b", SECRET_B));

    private Conduit signed(String id, String keyId, byte[] secret) throws Exception {
        Conduit c = started(builder(id)
                .signer(HmacSigner.sha256(keyId, secret))
                .verifier(KEYS)
                .maxClockSkew(Duration.ofSeconds(5)));
        c.registerPacket(SecurePacket.class).get(10, TimeUnit.SECONDS);
        c.registerPacket(OtherSecurePacket.class).get(10, TimeUnit.SECONDS);
        return c;
    }

    private static void ackSecure(Conduit c, AtomicInteger calls) {
        c.registerHandler(SecurePacket.class, (packet, ack) -> {
            calls.incrementAndGet();
            ack.accept(AckResponse.success("s", "ok " + packet.value()));
            return null;
        });
    }

    @Test
    @DisplayName("Signed packets and their signed ACKs pass between instances that share the keys")
    void signedRoundTrip() throws Exception {
        Conduit a = signed("signed-a", "server-a", SECRET_A);
        Conduit b = signed("signed-b", "server-b", SECRET_B);
        AtomicInteger calls = new AtomicInteger();
        ackSecure(b, calls);

        AckResponse ack = a.sendWithAck(new SecurePacket("v"), 10_000).get(15, TimeUnit.SECONDS);

        assertThat(ack.message()).isEqualTo("ok v");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(a.metrics().refused()).isZero();
        assertThat(b.metrics().refused()).isZero();
    }

    @Test
    @DisplayName("A wrong signature is refused: no handler, no NACK, counted")
    void badSignatureIsRefused() throws Exception {
        Conduit b = signed("signed-b", "server-b", SECRET_B);
        // Claims to be server-a, signs with a key that is not server-a's.
        Conduit forger = signed("forger", "server-a", "not-the-secret-of-server-a-at-all".getBytes(StandardCharsets.UTF_8));
        AtomicInteger calls = new AtomicInteger();
        ackSecure(b, calls);

        CompletableFuture<AckResponse> ack = forger.sendWithAck(new SecurePacket("forged"), 1500);

        assertThatThrownBy(() -> ack.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 1);
        assertThat(calls.get()).isZero();
        assertThat(forger.metrics().ackTimeouts()).isEqualTo(1);
    }

    @Test
    @DisplayName("An unsigned packet is refused by an instance that verifies")
    void unsignedIsRefused() throws Exception {
        Conduit b = signed("signed-b", "server-b", SECRET_B);
        conduit1.registerPacket(SecurePacket.class).get(10, TimeUnit.SECONDS);
        AtomicInteger calls = new AtomicInteger();
        ackSecure(b, calls);

        conduit1.send(new SecurePacket("plain")).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 1);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("A timestamp outside maxClockSkew is refused")
    void oldTimestampIsRefused() throws Exception {
        Conduit b = signed("signed-b", "server-b", SECRET_B);
        Conduit late = started(builder("late-clock")
                .signer(HmacSigner.sha256("server-a", SECRET_A))
                .clock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-5))));
        late.registerPacket(SecurePacket.class).get(10, TimeUnit.SECONDS);
        AtomicInteger calls = new AtomicInteger();
        ackSecure(b, calls);

        late.send(new SecurePacket("old")).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 1);
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("A signed packet replayed is refused; replayed on another channel too")
    void replaysAreRefused() throws Exception {
        Conduit a = signed("signed-a", "server-a", SECRET_A);
        Conduit b = signed("signed-b", "server-b", SECRET_B);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger otherCalls = new AtomicInteger();
        ackSecure(b, calls);
        b.registerHandler(OtherSecurePacket.class, (packet, ack) -> { otherCalls.incrementAndGet(); return null; });

        Transport attacker = rawTransport();
        List<byte[]> captured = new CopyOnWriteArrayList<>();
        attacker.subscribe("secure", (ch, data) -> captured.add(data)).get(10, TimeUnit.SECONDS);

        a.send(new SecurePacket("once")).get(10, TimeUnit.SECONDS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> calls.get() == 1 && captured.size() == 1);

        // Same bytes, same channel: a replay.
        attacker.broadcast("secure", captured.get(0)).get(10, TimeUnit.SECONDS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 1);
        // Same bytes, another channel the victim listens on.
        attacker.broadcast("secure-other", captured.get(0)).get(10, TimeUnit.SECONDS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 2);
        // Same bytes, sent as a unicast to the victim.
        attacker.sendTo("secure", "signed-b", captured.get(0)).get(10, TimeUnit.SECONDS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 3);

        sleepSettle();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(otherCalls.get()).isZero();
    }

    @Test
    @DisplayName("The verifier's policy decides who may send what")
    void policyRefusalIsRefused() throws Exception {
        Conduit b = started(builder("policy-b")
                .signer(HmacSigner.sha256("server-b", SECRET_B))
                .verifier(KEYS.and((keyId, env) -> "server-a".equals(keyId) && "test.secure.v1".equals(env.wireName())
                        ? Verdict.refuse("server-a may not send " + env.wireName())
                        : Verdict.accept())));
        b.registerPacket(SecurePacket.class).get(10, TimeUnit.SECONDS);
        Conduit a = signed("signed-a", "server-a", SECRET_A);
        AtomicInteger calls = new AtomicInteger();
        ackSecure(b, calls);

        a.send(new SecurePacket("not allowed")).get(10, TimeUnit.SECONDS);

        await().atMost(10, TimeUnit.SECONDS).until(() -> b.metrics().refused() == 1);
        assertThat(calls.get()).isZero();
        assertThat(b.metrics().byWire().get("test.secure.v1").refused()).isEqualTo(1);
    }

    @Test
    @DisplayName("A forged ACK is refused and the request keeps waiting for the real one")
    void forgedAckIsRefused() throws Exception {
        Conduit a = signed("signed-a", "server-a", SECRET_A);
        Conduit b = signed("signed-b", "server-b", SECRET_B);
        // b answers, but its answer travels through an instance with a wrong key: a cannot trust it.
        Conduit badResponder = started(builder("bad-responder")
                .signer(HmacSigner.sha256("server-b", "wrong-key-wrong-key-wrong-key-xx".getBytes(StandardCharsets.UTF_8)))
                .verifier(KEYS));
        badResponder.registerPacket(SecurePacket.class).get(10, TimeUnit.SECONDS);
        // The forged responder accepts a's request (a's signature is good) and answers with a bad signature.
        badResponder.registerHandler(SecurePacket.class, (packet, ack) -> { ack.accept(AckResponse.success("x", "forged")); return null; });
        AtomicInteger calls = new AtomicInteger();
        b.registerHandler(SecurePacket.class, (packet, ack) -> {
            calls.incrementAndGet();
            CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)
                    .execute(() -> ack.accept(AckResponse.success("s", "genuine")));
            return null;
        });

        AckResponse ack = a.sendWithAck(new SecurePacket("v"), 10_000).get(15, TimeUnit.SECONDS);

        assertThat(ack.message()).isEqualTo("genuine");
        await().atMost(10, TimeUnit.SECONDS).until(() -> a.metrics().refused() == 1);
    }

    // ===== 8. Metrics =====

    @Test
    @DisplayName("Counters: sent, received, unknown type, ACK timeouts, per wire name")
    void metricsCount() throws Exception {
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> null);
        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ack) -> null); // never answers

        conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "1")).get(10, TimeUnit.SECONDS);
        conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "2")).get(10, TimeUnit.SECONDS);
        CompletableFuture<AckResponse> unanswered = conduit1.sendWithAck(new ConfigReloadPacket("x"), 300);
        assertThatThrownBy(() -> unanswered.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);

        await().atMost(10, TimeUnit.SECONDS).until(() -> conduit2.metrics().received() == 3);
        assertThat(conduit1.metrics().sent()).isEqualTo(3);
        assertThat(conduit1.metrics().ackTimeouts()).isEqualTo(1);
        assertThat(conduit1.metrics().byWire().get("ChatMessagePacket").sent()).isEqualTo(2);
        assertThat(conduit1.metrics().byWire().get("ConfigReloadPacket").ackTimeouts()).isEqualTo(1);
        assertThat(conduit2.metrics().byWire().get("ChatMessagePacket").received()).isEqualTo(2);

        conduit1.registerPacket(RotationV1.class);
        conduit1.send(new RotationV1("s")).get(10, TimeUnit.SECONDS);
        await().atMost(10, TimeUnit.SECONDS).until(() -> conduit2.metrics().unknownType() == 1);
        assertThat(conduit2.metrics().byWire()).doesNotContainKey("test.rotation.v1");
    }
}
