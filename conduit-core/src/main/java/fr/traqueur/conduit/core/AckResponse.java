package fr.traqueur.conduit.core;

import java.time.Instant;

public record AckResponse(String packetId, boolean success, String message, Instant timestamp) {

    public static AckResponse success(String packetId) {
        return new AckResponse(packetId, true, null, Instant.now());
    }

    public static AckResponse success(String packetId, String message) {
        return new AckResponse(packetId, true, message, Instant.now());
    }

    public static AckResponse failure(String packetId, String message) {
        return new AckResponse(packetId, false, message, Instant.now());
    }

    public static AckResponse error(String message) {
        return failure("unknown", message);
    }

}
