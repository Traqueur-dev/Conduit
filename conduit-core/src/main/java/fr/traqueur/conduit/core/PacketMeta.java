package fr.traqueur.conduit.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify custom channel routing for packet types.
 * By default, packets are sent to the default channel.
 * Use this annotation to route packets to a specific custom channel.
 *
 * <p>Example:</p>
 * <pre>
 * {@literal @}PacketMeta(channel = "events")
 * public record EventPacket(String eventId) implements Packet {}
 * </pre>
 *
 * @author Traqueur
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PacketMeta {

    /**
     * The channel name to use for this packet type.
     *
     * @return the channel name
     */
    String channel();

}
