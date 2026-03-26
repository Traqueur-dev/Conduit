package fr.traqueur.conduit.core;

import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.packet.TargetableAcknowledgeablePacket;
import fr.traqueur.conduit.packet.TargetablePacket;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base test class for Conduit integration tests.
 * Contains common test scenarios that should work identically on all transports.
 *
 * @author Traqueur
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class BaseConduitIntegrationTest {

    protected Conduit conduit1;
    protected Conduit conduit2;

    // Test packets
    public record ChatMessagePacket(UUID senderId, String message) implements Packet {}

    @PacketMeta(channel = "player-events")
    public record PlayerJoinPacket(UUID playerId, String playerName) implements Packet {}

    public record PlayerKickPacket(UUID playerId, String reason) implements TargetablePacket {}

    public record ConfigReloadPacket(String config) implements AcknowledgeablePacket {}

    public record DataSyncPacket(String data) implements TargetableAcknowledgeablePacket {}

    /**
     * Subclasses must implement this to set up conduit instances.
     */
    protected abstract void setupConduitInstances() throws Exception;

    /**
     * Subclasses must implement this to set up conduit instances with compression.
     */
    protected abstract void setupConduitInstancesWithCompression() throws Exception;

    /**
     * Subclasses must implement this to tear down conduit instances.
     */
    protected abstract void teardownConduitInstances();

    @BeforeEach
    void setUp() throws Exception {
        setupConduitInstances();

        // Register packets on both instances
        registerPackets(conduit1);
        registerPackets(conduit2);

        // Start both instances
        conduit1.start();
        conduit2.start();

        // Wait for connections to be fully ready
        Thread.sleep(2000);
    }

    @AfterEach
    void tearDown() {
        teardownConduitInstances();
        Conduit.resetAll();
    }

    private void registerPackets(Conduit conduit) {
        conduit.registerPacket(ChatMessagePacket.class);
        conduit.registerPacket(PlayerJoinPacket.class);
        conduit.registerPacket(PlayerKickPacket.class);
        conduit.registerPacket(ConfigReloadPacket.class);
        conduit.registerPacket(DataSyncPacket.class);
    }

    @Test
    @Order(1)
    @DisplayName("Should send and receive simple broadcast packet")
    void shouldSendAndReceiveBroadcastPacket() throws Exception {
        // Given
        UUID senderId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessagePacket> receivedPacket = new AtomicReference<>();

        // Register handler on instance 2
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
            receivedPacket.set(packet);
            latch.countDown();
            return null;
        });

        Thread.sleep(1000);

        // When - Send from instance 1
        conduit1.send(new ChatMessagePacket(senderId, "Hello World"));

        // Then
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPacket.get()).isNotNull();
        assertThat(receivedPacket.get().senderId()).isEqualTo(senderId);
        assertThat(receivedPacket.get().message()).isEqualTo("Hello World");
    }

    @Test
    @Order(2)
    @DisplayName("Should respect @PacketMeta channel annotation")
    void shouldRespectPacketMetaChannel() throws Exception {
        // Given
        UUID playerId = UUID.randomUUID();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<PlayerJoinPacket> receivedPacket = new AtomicReference<>();

        conduit2.registerHandler(PlayerJoinPacket.class, (packet, ackCallback) -> {
            receivedPacket.set(packet);
            latch.countDown();
            return null;
        });

        Thread.sleep(1000);

        // When
        conduit1.send(new PlayerJoinPacket(playerId, "TestPlayer"));

        // Then
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPacket.get()).isNotNull();
        assertThat(receivedPacket.get().playerId()).isEqualTo(playerId);
    }

    @Test
    @Order(3)
    @DisplayName("Should send unicast packet to specific target")
    void shouldSendUnicastPacket() throws Exception {
        // Given
        UUID playerId = UUID.randomUUID();
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        conduit1.registerHandler(PlayerKickPacket.class, (packet, ackCallback) -> {
            latch1.countDown();
            return null;
        });

        conduit2.registerHandler(PlayerKickPacket.class, (packet, ackCallback) -> {
            latch2.countDown();
            return null;
        });

        Thread.sleep(1000);

        // When - Send to instance-2 only
        conduit1.sendTo(new PlayerKickPacket(playerId, "Cheating"), "instance-2");

        // Then
        assertThat(latch2.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(latch1.await(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("Should send packet with ACK and receive acknowledgment")
    void shouldSendPacketWithAck() throws Exception {
        // Given
        CountDownLatch handlerLatch = new CountDownLatch(1);

        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ackCallback) -> {
            handlerLatch.countDown();
            if (ackCallback != null) {
                ackCallback.accept(AckResponse.success("config-123", "Config reloaded successfully"));
            }
            return null;
        });

        Thread.sleep(1000);

        // When
        CompletableFuture<AckResponse> ackFuture = conduit1.sendWithAck(new ConfigReloadPacket("server.properties"), 15000L);

        // Then
        assertThat(handlerLatch.await(10, TimeUnit.SECONDS))
                .as("Handler should be called")
                .isTrue();

        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(ackFuture.isDone()).isTrue());

        AckResponse ack = ackFuture.get();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isTrue();
        assertThat(ack.message()).isEqualTo("Config reloaded successfully");
    }

    @Test
    @Order(5)
    @DisplayName("Should send unicast packet with ACK to specific target")
    void shouldSendUnicastPacketWithAck() throws Exception {
        // Given
        CountDownLatch handlerLatch = new CountDownLatch(1);

        conduit2.registerHandler(DataSyncPacket.class, (packet, ackCallback) -> {
            handlerLatch.countDown();
            if (ackCallback != null) {
                ackCallback.accept(AckResponse.success("sync-456", "Data synced: " + packet.data()));
            }
            return null;
        });

        Thread.sleep(1000);

        // When
        CompletableFuture<AckResponse> ackFuture =
                conduit1.sendWithAck(new DataSyncPacket("important-data"), "instance-2", 15000L);

        // Then
        assertThat(handlerLatch.await(10, TimeUnit.SECONDS)).isTrue();

        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(ackFuture.isDone()).isTrue());

        AckResponse ack = ackFuture.get();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isTrue();
        assertThat(ack.message()).contains("important-data");
    }

    @Test
    @Order(6)
    @DisplayName("Should receive error ACK when no handler is registered")
    void shouldReceiveErrorAckWhenNoHandler() throws Exception {
        // Given - No handler registered

        // When
        CompletableFuture<AckResponse> ackFuture =
                conduit1.sendWithAck(new ConfigReloadPacket("test"), 10000L);

        // Then
        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(ackFuture.isDone()).isTrue());

        AckResponse ack = ackFuture.get();
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isFalse();
        assertThat(ack.message()).contains("No handler registered");
    }

    @Test
    @Order(7)
    @DisplayName("Should handle multiple receivers for broadcast")
    void shouldHandleMultipleBroadcastReceivers() throws Exception {
        // Given
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);

        conduit1.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
            latch1.countDown();
            return null;
        });

        conduit2.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
            latch2.countDown();
            return null;
        });

        Thread.sleep(1000);

        // When - Broadcast should reach both, but we send from conduit1
        // Note: conduit1 will ignore its own broadcast
        conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "Broadcast message"));

        // Then - Only conduit2 should receive (conduit1 filters its own)
        assertThat(latch2.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(latch1.await(2, TimeUnit.SECONDS)).isFalse(); // Should NOT receive its own
    }

    @Test
    @Order(8)
    @DisplayName("Should handle packet with compression (Gzip)")
    void shouldHandlePacketWithCompression() throws Exception {
        // Given - Recreate conduit with Gzip compression
        if (conduit1 != null) conduit1.shutdown();
        if (conduit2 != null) conduit2.shutdown();

        setupConduitInstancesWithCompression();

        conduit1.registerPacket(ChatMessagePacket.class);
        conduit2.registerPacket(ChatMessagePacket.class);
        conduit1.start();
        conduit2.start();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatMessagePacket> receivedPacket = new AtomicReference<>();

        conduit2.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
            receivedPacket.set(packet);
            latch.countDown();
            return null;
        });

        Thread.sleep(1000);

        // When
        String largeMessage = "A".repeat(1000);
        conduit1.send(new ChatMessagePacket(UUID.randomUUID(), largeMessage));

        // Then
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPacket.get()).isNotNull();
        assertThat(receivedPacket.get().message()).isEqualTo(largeMessage);
    }

    @Test
    @Order(9)
    @DisplayName("Should handle packet with async handler on different thread")
    void shouldHandlePacketWithAsyncHandler() throws Exception {
        // Given
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<String> handlerThread = new AtomicReference<>();
        String mainThread = Thread.currentThread().getName();
        CountDownLatch handlerLatch = new CountDownLatch(1);

        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ackCallback) ->
            CompletableFuture.runAsync(() -> {
                handlerThread.set(Thread.currentThread().getName());
                if (ackCallback != null) {
                    ackCallback.accept(AckResponse.success("async-test", "Handled async"));
                }
                handlerLatch.countDown();
            }, executor)
        );

        Thread.sleep(1000);

        // When
        CompletableFuture<AckResponse> ackFuture =
                conduit1.sendWithAck(new ConfigReloadPacket("async-config"), 15000L);

        // Then
        assertThat(handlerLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handlerThread.get()).isNotEqualTo(mainThread);

        AckResponse ack = ackFuture.get(15, TimeUnit.SECONDS);
        assertThat(ack).isNotNull();
        assertThat(ack.success()).isTrue();
        assertThat(ack.message()).isEqualTo("Handled async");

        executor.shutdown();
    }
}