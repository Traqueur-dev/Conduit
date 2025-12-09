package examples.basic;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.packet.TargetablePacket;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

import java.util.UUID;

/**
 * Unicast messaging example.
 * Shows how to send messages to a specific instance.
 */
public class UnicastExample {

    // Packet that can be targeted to specific instance
    public record DirectMessagePacket(UUID recipientId, String message) implements TargetablePacket {}

    public static void main(String[] args) throws Exception {
        // Setup three instances
        Conduit server1 = setupConduit("server-1");
        Conduit server2 = setupConduit("server-2");
        Conduit server3 = setupConduit("server-3");

        // Register packet type on all instances
        server1.registerPacket(DirectMessagePacket.class);
        server2.registerPacket(DirectMessagePacket.class);
        server3.registerPacket(DirectMessagePacket.class);

        // Register handlers on all instances
        registerHandler(server1, "Server-1");
        registerHandler(server2, "Server-2");
        registerHandler(server3, "Server-3");

        // Start all instances
        server1.start();
        server2.start();
        server3.start();

        Thread.sleep(2000);

        // Send message only to server-2
        System.out.println("Sending message to server-2 only...");
        DirectMessagePacket message = new DirectMessagePacket(
                UUID.randomUUID(),
                "This message is only for server-2"
        );
        message.sendTo("server-2");

        // Wait for delivery
        Thread.sleep(3000);

        // Cleanup
        server1.shutdown();
        server2.shutdown();
        server3.shutdown();
    }

    private static void registerHandler(Conduit conduit, String instanceName) {
        conduit.registerHandler(DirectMessagePacket.class, (packet, ackCallback) -> {
            System.out.println(instanceName + " received: " + packet.message());
            return HandlerResult.SUCCESS;
        });
    }

    private static Conduit setupConduit(String instanceId) {
        RedisConfig config = RedisConfig.localhost();
        RedisTransport transport = new RedisTransport(config);

        return Conduit.builder()
                .transport(transport)
                .instanceId(instanceId)
                .build();
    }
}
