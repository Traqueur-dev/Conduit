package fr.traqueur.conduit.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Routing and naming of a packet type.
 *
 * <pre>
 * {@literal @}PacketMeta(channel = "shop", wire = "shop.rotation.v1")
 * public record ShopRotationPacket(String shopId, String periodKey) implements Packet {}
 * </pre>
 *
 * @author Traqueur
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PacketMeta {

    /**
     * The channel this packet type travels on. Empty: the Conduit's default channel.
     *
     * @return the channel name
     */
    String channel() default "";

    /**
     * The name of this type on the wire. It is what a receiver uses to find the class, so it must
     * be unique across everything that shares the bus and stable across renames. Empty: the class
     * simple name (refused when the Conduit is built with
     * {@link Conduit.ConduitBuilder#requireWireNames()}).
     *
     * @return the wire name
     */
    String wire() default "";
}
