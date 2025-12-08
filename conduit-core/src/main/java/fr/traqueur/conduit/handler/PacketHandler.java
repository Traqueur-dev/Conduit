package fr.traqueur.conduit.handler;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.packet.Packet;

import java.util.function.Consumer;

/**
 * Handler for processing received packets.
 * 
 * @param <T> the type of packet this handler processes
 *
 * @author Traqueur
 */
@FunctionalInterface
public interface PacketHandler<T extends Packet> {
    
    /**
     * Handles a received packet.
     * 
     * @param packet the received packet
     * @param ackCallback callback to send acknowledgment (null if packet doesn't require ACK)
     * @return the result of handling the packet
     */
    HandlerResult handle(T packet, Consumer<AckResponse> ackCallback);
}