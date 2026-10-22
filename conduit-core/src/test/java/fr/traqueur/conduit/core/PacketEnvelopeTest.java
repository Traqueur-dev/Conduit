package fr.traqueur.conduit.core;

import fr.traqueur.conduit.security.HmacSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PacketEnvelopeTest {

    private static final HmacSigner SIGNER = HmacSigner.sha256("key-1", "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("A packet envelope round-trips, version byte first")
    void packetRoundTrip() throws Exception {
        byte[] bytes = PacketEnvelope.packet("id-1", 1234L, "sender", "ns:", "chan", "target", "wire.v1",
                "chan:ack", null, new byte[]{1, 2, 3}).encode(null);

        assertThat(bytes[0]).isEqualTo(PacketEnvelope.VERSION);
        PacketEnvelope env = PacketEnvelope.decode(bytes);
        assertThat(env.packetId()).isEqualTo("id-1");
        assertThat(env.timestampMillis()).isEqualTo(1234L);
        assertThat(env.senderId()).isEqualTo("sender");
        assertThat(env.namespace()).isEqualTo("ns:");
        assertThat(env.channel()).isEqualTo("chan");
        assertThat(env.target()).isEqualTo("target");
        assertThat(env.wireName()).isEqualTo("wire.v1");
        assertThat(env.requiresAck()).isTrue();
        assertThat(env.replyChannel()).isEqualTo("chan:ack");
        assertThat(env.payload()).containsExactly(1, 2, 3);
        assertThat(env.isSigned()).isFalse();
        assertThat(env.signedData()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("An ACK envelope round-trips, signed, the signature covering everything before it")
    void ackRoundTripSigned() throws Exception {
        AckResponse ack = new AckResponse(null, false, "nope", Instant.ofEpochMilli(99));
        byte[] bytes = PacketEnvelope.ackResponse("id-2", 5L, "responder", "", "c:ack", "requester", "id-1",
                SIGNER.keyId(), ack).encode(SIGNER);

        PacketEnvelope env = PacketEnvelope.decode(bytes);
        assertThat(env.isAckResponse()).isTrue();
        assertThat(env.ackId()).isEqualTo("id-1");
        assertThat(env.ackResponseBody()).isEqualTo(new AckResponse("id-1", false, "nope", Instant.ofEpochMilli(99)));
        assertThat(env.keyId()).isEqualTo("key-1");
        assertThat(env.signature()).isEqualTo(SIGNER.sign(env.signedData()));
        assertThat(bytes).startsWith(env.signedData());
    }

    @Test
    @DisplayName("Malformed input is rejected without allocating what it claims")
    void malformedIsRejected() {
        byte[] good = PacketEnvelope.packet("id", 1L, "s", "", "c", null, "w", null, null, new byte[10]).encode(null);

        assertThatThrownBy(() -> PacketEnvelope.decode(new byte[0])).isInstanceOf(PacketEnvelope.MalformedEnvelopeException.class);
        // A 1.x envelope starts with its flags byte (0..3): refused as an unknown version.
        byte[] legacy = good.clone();
        legacy[0] = 0x01;
        assertThatThrownBy(() -> PacketEnvelope.decode(legacy)).hasMessageContaining("version");
        assertThatThrownBy(() -> PacketEnvelope.decode(Arrays.copyOf(good, good.length - 3))).hasMessageContaining("length");
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThatThrownBy(() -> PacketEnvelope.decode(trailing)).hasMessageContaining("trailing");
        byte[] huge = good.clone();
        ByteBuffer.wrap(huge).putInt(10, Integer.MAX_VALUE); // packetId length
        assertThatThrownBy(() -> PacketEnvelope.decode(huge)).hasMessageContaining("length");
    }

    @Test
    @DisplayName("Signer and key id go together")
    void signerNeedsKeyId() {
        PacketEnvelope unsigned = PacketEnvelope.packet("id", 1L, "s", "", "c", null, "w", null, null, null);
        assertThatThrownBy(() -> unsigned.encode(SIGNER)).isInstanceOf(IllegalArgumentException.class);
    }
}
