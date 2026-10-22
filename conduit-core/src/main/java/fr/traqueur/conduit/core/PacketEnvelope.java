package fr.traqueur.conduit.core;

import fr.traqueur.conduit.security.EnvelopeSigner;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

/**
 * The unit that travels on the wire: a packet (or an acknowledgment) plus everything needed to
 * route it, date it, and authenticate it.
 *
 * <p>Format, version 2 (every integer is big-endian; {@code str} is an {@code int32} length
 * followed by that many UTF-8 bytes, {@code -1} meaning {@code null}; {@code bytes} is an
 * {@code int32} length followed by the bytes):</p>
 * <pre>
 * u8    version        always 0x02
 * u8    flags          bit 0 requiresAck, bit 1 ackResponse, bit 2 signed
 * i64   timestamp      sender clock, epoch milliseconds
 * str   packetId       unique per envelope (anti-replay key; the ACK id of a request)
 * str   senderId       instanceId of the sender
 * str   namespace      the transport's channel prefix ("" when none)
 * str   channel        logical channel the envelope was published on
 * str   target         instanceId of a unicast target, null for a broadcast
 * str   wireName       packet type name, null for an ACK response
 * str   replyChannel   channel the sender listens on for ACKs, null unless requiresAck
 * str   ackId          ACK response only: packetId of the request it answers
 * str   keyId          signer key id, present only when signed
 * --- ACK response only ---
 * u8    success
 * str   message
 * i64   ackTimestamp   epoch milliseconds
 * ---
 * bytes payload        serialized then compressed packet (empty for an ACK response)
 * --- signed only ---
 * bytes signature      over every byte above, from version to payload inclusive
 * </pre>
 *
 * <p>There are no free-form metadata: every byte but the signature is signed, so nothing in an
 * envelope can be altered by whoever relays it.</p>
 *
 * @author Traqueur
 */
public final class PacketEnvelope {

    /** The only format version this build reads and writes. */
    public static final byte VERSION = 0x02;

    private static final int FLAG_REQUIRES_ACK = 0x01;
    private static final int FLAG_ACK_RESPONSE = 0x02;
    private static final int FLAG_SIGNED = 0x04;

    private final boolean requiresAck;
    private final boolean ackResponse;
    private final long timestamp;
    private final String packetId;
    private final String senderId;
    private final String namespace;
    private final String channel;
    private final String target;
    private final String wireName;
    private final String replyChannel;
    private final String ackId;
    private final String keyId;
    private final AckResponse ack;
    private final byte[] payload;

    // Set by decode()/sign(): the exact bytes covered by the signature, and the signature itself.
    private byte[] signedData;
    private byte[] signature;

    private PacketEnvelope(boolean requiresAck, boolean ackResponse, long timestamp, String packetId,
                           String senderId, String namespace, String channel, String target,
                           String wireName, String replyChannel, String ackId, String keyId,
                           AckResponse ack, byte[] payload) {
        this.requiresAck = requiresAck;
        this.ackResponse = ackResponse;
        this.timestamp = timestamp;
        this.packetId = packetId;
        this.senderId = senderId;
        this.namespace = namespace == null ? "" : namespace;
        this.channel = channel;
        this.target = target;
        this.wireName = wireName;
        this.replyChannel = replyChannel;
        this.ackId = ackId;
        this.keyId = keyId;
        this.ack = ack;
        this.payload = payload == null ? new byte[0] : payload;
    }

    /**
     * Creates the envelope of a packet.
     *
     * @param packetId     unique id of this envelope
     * @param timestamp    sender clock, epoch milliseconds
     * @param senderId     sender instance id
     * @param namespace    transport channel prefix
     * @param channel      logical channel
     * @param target       unicast target, or null for a broadcast
     * @param wireName     packet type name
     * @param replyChannel channel to answer on, or null if no ACK is required
     * @param keyId        signer key id, or null if unsigned
     * @param payload      serialized and compressed packet
     * @return the envelope
     */
    public static PacketEnvelope packet(String packetId, long timestamp, String senderId, String namespace,
                                        String channel, String target, String wireName,
                                        String replyChannel, String keyId, byte[] payload) {
        return new PacketEnvelope(replyChannel != null, false, timestamp, packetId, senderId, namespace,
                channel, target, wireName, replyChannel, null, keyId, null, payload);
    }

    /**
     * Creates the envelope of an acknowledgment response.
     *
     * @param packetId  unique id of this envelope
     * @param timestamp sender clock, epoch milliseconds
     * @param senderId  responder instance id
     * @param namespace transport channel prefix
     * @param channel   reply channel it is published on
     * @param target    instance id of the requester
     * @param ackId     packetId of the request
     * @param keyId     signer key id, or null if unsigned
     * @param ack       the response
     * @return the envelope
     */
    public static PacketEnvelope ackResponse(String packetId, long timestamp, String senderId, String namespace,
                                             String channel, String target, String ackId, String keyId,
                                             AckResponse ack) {
        return new PacketEnvelope(false, true, timestamp, packetId, senderId, namespace,
                channel, target, null, null, ackId, keyId, ack, null);
    }

    /** @return whether the sender waits for an acknowledgment */
    public boolean requiresAck() { return requiresAck; }

    /** @return whether this envelope carries an acknowledgment response */
    public boolean isAckResponse() { return ackResponse; }

    /** @return sender clock when the envelope was built */
    public Instant timestamp() { return Instant.ofEpochMilli(timestamp); }

    /** @return sender clock when the envelope was built, epoch milliseconds */
    public long timestampMillis() { return timestamp; }

    /** @return unique id of this envelope */
    public String packetId() { return packetId; }

    /** @return instance id of the sender */
    public String senderId() { return senderId; }

    /** @return the transport channel prefix the envelope was built for, never null */
    public String namespace() { return namespace; }

    /** @return logical channel the envelope was published on */
    public String channel() { return channel; }

    /** @return unicast target instance id, or null for a broadcast */
    public String target() { return target; }

    /** @return packet type name, null for an ACK response */
    public String wireName() { return wireName; }

    /** @return channel the sender listens on for ACKs, or null */
    public String replyChannel() { return replyChannel; }

    /** @return for an ACK response, the packetId of the request it answers */
    public String ackId() { return ackId; }

    /** @return the signer key id, or null if unsigned */
    public String keyId() { return keyId; }

    /** @return the acknowledgment response, or null */
    public AckResponse ackResponseBody() { return ack; }

    /** @return the serialized and compressed payload (empty for an ACK response); a copy */
    public byte[] payload() { return payload.clone(); }

    byte[] payloadUnsafe() { return payload; }

    /** @return whether the envelope carries a signature */
    public boolean isSigned() { return signature != null; }

    /**
     * The bytes the signature covers. Only meaningful on an envelope that was decoded or encoded.
     *
     * @return a copy of the signed bytes, or null
     */
    public byte[] signedData() { return signedData == null ? null : signedData.clone(); }

    /** @return a copy of the signature, or null */
    public byte[] signature() { return signature == null ? null : signature.clone(); }

    byte[] signedDataUnsafe() { return signedData; }

    byte[] signatureUnsafe() { return signature; }

    /**
     * Encodes the envelope, signing it with {@code signer} when it is not null.
     *
     * @param signer the signer, or null for an unsigned envelope
     * @return the encoded bytes
     * @throws IllegalArgumentException if a signer is given but the envelope has no key id, or the reverse
     */
    public byte[] encode(EnvelopeSigner signer) {
        if ((signer == null) != (keyId == null)) {
            throw new IllegalArgumentException("keyId must be set exactly when a signer is used");
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 + payload.length);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeByte(VERSION);
            int flags = 0;
            if (requiresAck) flags |= FLAG_REQUIRES_ACK;
            if (ackResponse) flags |= FLAG_ACK_RESPONSE;
            if (keyId != null) flags |= FLAG_SIGNED;
            out.writeByte(flags);
            out.writeLong(timestamp);
            writeString(out, packetId);
            writeString(out, senderId);
            writeString(out, namespace);
            writeString(out, channel);
            writeString(out, target);
            writeString(out, wireName);
            writeString(out, replyChannel);
            writeString(out, ackId);
            writeString(out, keyId);
            if (ackResponse) {
                out.writeBoolean(ack.success());
                writeString(out, ack.message());
                out.writeLong(ack.timestamp() == null ? 0L : ack.timestamp().toEpochMilli());
            }
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            if (signer == null) {
                this.signedData = baos.toByteArray();
                return signedData.clone();
            }
            byte[] covered = baos.toByteArray();
            byte[] sig = signer.sign(covered);
            out.writeInt(sig.length);
            out.write(sig);
            out.flush();
            this.signedData = covered;
            this.signature = sig.clone();
            return baos.toByteArray();
        } catch (IOException e) {
            // A ByteArrayOutputStream never throws: reaching this is a bug, not an I/O condition.
            throw new IllegalStateException("In-memory encoding failed", e);
        }
    }

    /**
     * Decodes an envelope. Every length is checked against the remaining bytes, so a hostile
     * input fails here instead of allocating what it claims.
     *
     * @param data the encoded bytes
     * @return the envelope
     * @throws MalformedEnvelopeException if the bytes are not a version-2 envelope
     */
    public static PacketEnvelope decode(byte[] data) throws MalformedEnvelopeException {
        if (data == null || data.length == 0) {
            throw new MalformedEnvelopeException("empty envelope");
        }
        ByteBuffer in = ByteBuffer.wrap(data);
        try {
            byte version = in.get();
            if (version != VERSION) {
                throw new MalformedEnvelopeException("unsupported envelope version 0x"
                        + Integer.toHexString(version & 0xFF));
            }
            int flags = in.get() & 0xFF;
            long timestamp = in.getLong();
            String packetId = readString(in);
            String senderId = readString(in);
            String namespace = readString(in);
            String channel = readString(in);
            String target = readString(in);
            String wireName = readString(in);
            String replyChannel = readString(in);
            String ackId = readString(in);
            String keyId = readString(in);
            boolean ackResponse = (flags & FLAG_ACK_RESPONSE) != 0;
            AckResponse ack = null;
            if (ackResponse) {
                boolean success = in.get() != 0;
                String message = readString(in);
                long ackTs = in.getLong();
                ack = new AckResponse(ackId, success, message, Instant.ofEpochMilli(ackTs));
            }
            byte[] payload = readBytes(in);
            int signedEnd = in.position();
            boolean signed = (flags & FLAG_SIGNED) != 0;
            byte[] signature = signed ? readBytes(in) : null;
            if (in.hasRemaining()) {
                throw new MalformedEnvelopeException(in.remaining() + " trailing bytes");
            }
            if (signed != (keyId != null)) {
                throw new MalformedEnvelopeException("signed flag and keyId disagree");
            }
            if (packetId == null || senderId == null || channel == null) {
                throw new MalformedEnvelopeException("missing packetId, senderId or channel");
            }
            if (ackResponse ? ackId == null : wireName == null) {
                throw new MalformedEnvelopeException(ackResponse ? "ACK response without ackId" : "packet without wire name");
            }
            if ((flags & FLAG_REQUIRES_ACK) != 0 && replyChannel == null) {
                throw new MalformedEnvelopeException("ACK required but no reply channel");
            }
            PacketEnvelope envelope = new PacketEnvelope((flags & FLAG_REQUIRES_ACK) != 0, ackResponse,
                    timestamp, packetId, senderId, namespace, channel, target, wireName, replyChannel,
                    ackId, keyId, ack, payload);
            envelope.signedData = Arrays.copyOf(data, signedEnd);
            envelope.signature = signature;
            return envelope;
        } catch (BufferUnderflowException e) {
            throw new MalformedEnvelopeException("truncated envelope");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(ByteBuffer in) throws MalformedEnvelopeException {
        int length = in.getInt();
        if (length == -1) {
            return null;
        }
        if (length < 0 || length > in.remaining()) {
            throw new MalformedEnvelopeException("invalid string length " + length);
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer in) throws MalformedEnvelopeException {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) {
            throw new MalformedEnvelopeException("invalid byte array length " + length);
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return bytes;
    }

    @Override
    public String toString() {
        return "PacketEnvelope[" + (ackResponse ? "ack " + ackId : wireName)
                + ", packetId=" + packetId + ", sender=" + senderId
                + ", channel=" + namespace + channel + (target != null ? " -> " + target : "")
                + (keyId != null ? ", keyId=" + keyId : "") + "]";
    }

    /**
     * Thrown when bytes cannot be decoded as an envelope.
     */
    public static final class MalformedEnvelopeException extends Exception {
        /**
         * @param message why the bytes were rejected
         */
        public MalformedEnvelopeException(String message) {
            super(message);
        }
    }
}
