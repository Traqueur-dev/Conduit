package fr.traqueur.conduit.core;

import fr.traqueur.conduit.memory.InMemoryBus;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.security.HmacSigner;
import fr.traqueur.conduit.security.HmacVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConduitUnitTest {

    public record Ping(String value) implements Packet {}

    @AfterEach
    void reset() {
        Conduit.resetAll();
    }

    @Test
    @DisplayName("Packet.send() without a default instance fails with a clear message")
    void sendWithoutDefaultInstance() {
        assertThatThrownBy(() -> new Ping("x").send())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default Conduit instance")
                .hasMessageContaining("unregistered");
    }

    @Test
    @DisplayName("An unregistered instance is neither the default nor found by name")
    void unregisteredStaysOutOfTheRegistry() {
        Conduit hidden = Conduit.builder().transport(new InMemoryBus().transport()).name("hidden").unregistered().build();

        assertThatThrownBy(Conduit::getInstance).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Conduit.getInstance("hidden")).isInstanceOf(IllegalArgumentException.class);

        Conduit visible = Conduit.builder().transport(new InMemoryBus().transport()).name("visible").build();
        assertThat(Conduit.getInstance()).isSameAs(visible);
        assertThat(Conduit.getInstance("visible")).isSameAs(visible);
        hidden.shutdown();
        assertThat(Conduit.getInstance()).isSameAs(visible);
    }

    @Test
    @DisplayName("Two instances under one registry name are refused")
    void duplicateNameIsRefused() {
        Conduit.builder().transport(new InMemoryBus().transport()).name("dup").build();
        assertThatThrownBy(() -> Conduit.builder().transport(new InMemoryBus().transport()).name("dup").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dup");
    }

    @Test
    @DisplayName("Shutdown fails the pending ACKs and removes the instance from the registry")
    void shutdownFailsPendingAcks() throws Exception {
        InMemoryBus bus = new InMemoryBus();
        Conduit conduit = Conduit.builder().transport(bus.transport()).name("x").build();
        conduit.registerPacket(Ping.class);
        conduit.start();

        var pending = conduit.sendWithAck(new Ping("nobody answers"), 60_000);
        conduit.shutdown();

        assertThat(pending).isCompletedExceptionally();
        assertThatThrownBy(() -> Conduit.getInstance("x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(conduit::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Secrets never appear in toString()")
    void secretsStayOutOfToString() {
        byte[] secret = "super-secret-value-32-bytes-long".getBytes(StandardCharsets.UTF_8);
        assertThat(HmacSigner.sha256("k", secret).toString()).doesNotContain("super-secret");
        assertThat(HmacVerifier.sha256(Map.of("k", secret)).toString()).doesNotContain("super-secret").contains("k");
    }

    @Test
    @DisplayName("The reply channel is reserved")
    void ackChannelIsReserved() {
        @PacketMeta(channel = "conduit:packets:ack")
        record Squatter() implements Packet {}
        Conduit conduit = Conduit.builder().transport(new InMemoryBus().transport()).unregistered().build();
        assertThatThrownBy(() -> conduit.registerPacket(Squatter.class)).isInstanceOf(IllegalArgumentException.class);
    }
}
