package fr.traqueur.conduit.core;

import fr.traqueur.conduit.memory.InMemoryBus;
import fr.traqueur.conduit.transport.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared suite on the synchronous in-memory bus — what proves it behaves like Redis — plus
 * the failures only it can simulate.
 */
class InMemoryConduitIntegrationTest extends BaseConduitIntegrationTest {

    private final InMemoryBus bus = new InMemoryBus();

    @Override
    protected Transport newTransport(String channelPrefix) {
        return bus.transport(channelPrefix);
    }

    @Override
    protected Duration settle() {
        return Duration.ZERO; // Synchronous: whatever was going to arrive already has.
    }

    @Test
    @DisplayName("Synchronous: the handler has run, and the ACK is in, when send returns")
    void synchronousDelivery() {
        AtomicInteger got = new AtomicInteger();
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got.incrementAndGet(); return null; });
        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ack) -> { ack.accept(AckResponse.success("x")); return null; });

        CompletableFuture<Void> sent = conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "now"));
        CompletableFuture<AckResponse> acked = conduit1.sendWithAck(new ConfigReloadPacket("now"), 1000);

        assertThat(sent).isCompleted();
        assertThat(got.get()).isEqualTo(1);
        assertThat(acked).isCompleted();
        assertThat(acked.join().success()).isTrue();
    }

    @Test
    @DisplayName("disconnect(): sends fail and nothing is received until reconnect()")
    void disconnect() {
        AtomicInteger got = new AtomicInteger();
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got.incrementAndGet(); return null; });

        bus.disconnect(conduit1.transport());
        assertThat(conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "x"))).isCompletedExceptionally();
        bus.reconnect(conduit1.transport());

        bus.disconnect(conduit2.transport());
        assertThat(conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "x"))).isCompleted();
        assertThat(got.get()).isZero();

        bus.reconnect(conduit2.transport());
        conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "x"));
        assertThat(got.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("dropNext(n) loses n messages that the sender believes sent")
    void dropNext() {
        AtomicInteger got = new AtomicInteger();
        conduit2.registerHandler(ChatMessagePacket.class, (packet, ack) -> { got.incrementAndGet(); return null; });

        bus.dropNext(2);
        for (int i = 0; i < 3; i++) {
            assertThat(conduit1.send(new ChatMessagePacket(UUID.randomUUID(), "m" + i))).isCompleted();
        }
        assertThat(got.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("dropAcks(n): the request times out though the handler answered")
    void dropAcks() {
        AtomicInteger got = new AtomicInteger();
        conduit2.registerHandler(ConfigReloadPacket.class, (packet, ack) -> {
            got.incrementAndGet();
            ack.accept(AckResponse.success("x"));
            return null;
        });

        bus.dropAcks(1);
        CompletableFuture<AckResponse> lost = conduit1.sendWithAck(new ConfigReloadPacket("x"), 200);
        assertThatThrownBy(() -> lost.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
        assertThat(got.get()).isEqualTo(1);
        assertThat(conduit1.metrics().ackTimeouts()).isEqualTo(1);

        assertThat(conduit1.sendWithAck(new ConfigReloadPacket("x"), 1000).join().success()).isTrue();
    }
}
