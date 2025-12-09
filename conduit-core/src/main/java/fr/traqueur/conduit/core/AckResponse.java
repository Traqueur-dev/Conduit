package fr.traqueur.conduit.core;

import java.time.Instant;

/**
 * Represents an acknowledgment response for a packet transmission.
 * Contains information about whether the packet was successfully processed,
 * along with optional messages and timestamp.
 *
 * @param packetId the unique identifier of the acknowledged packet
 * @param success whether the packet was processed successfully
 * @param message optional message providing additional information
 * @param timestamp when the acknowledgment was created
 *
 * @author Traqueur
 */
public record AckResponse(String packetId, boolean success, String message, Instant timestamp) {

    /**
     * Creates a successful acknowledgment response without a message.
     *
     * @param packetId the packet identifier
     * @return a success AckResponse
     */
    public static AckResponse success(String packetId) {
        return new AckResponse(packetId, true, null, Instant.now());
    }

    /**
     * Creates a successful acknowledgment response with a message.
     *
     * @param packetId the packet identifier
     * @param message additional success message
     * @return a success AckResponse
     */
    public static AckResponse success(String packetId, String message) {
        return new AckResponse(packetId, true, message, Instant.now());
    }

    /**
     * Creates a failure acknowledgment response.
     *
     * @param packetId the packet identifier
     * @param message error or failure message
     * @return a failure AckResponse
     */
    public static AckResponse failure(String packetId, String message) {
        return new AckResponse(packetId, false, message, Instant.now());
    }

    /**
     * Creates an error acknowledgment response with unknown packet ID.
     *
     * @param message error message
     * @return an error AckResponse
     */
    public static AckResponse error(String message) {
        return failure("unknown", message);
    }

}
