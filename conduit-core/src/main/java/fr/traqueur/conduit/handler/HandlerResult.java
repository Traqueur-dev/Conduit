package fr.traqueur.conduit.handler;

/**
 * Represents the result of packet handling.
 *
 * @author Traqueur
 */
public enum HandlerResult {
    
    /**
     * The packet was processed successfully.
     */
    SUCCESS,
    
    /**
     * An error occurred while processing the packet.
     */
    ERROR,
    
    /**
     * The packet could not be handled (no appropriate handler).
     */
    CANT_HANDLE
}