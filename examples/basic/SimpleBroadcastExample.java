package examples.basic;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

import java.util.UUID;

/**
 * Simple broadcast messaging example.
 * Shows how to send messages to all connected instances.
 */
public class SimpleBroadcastExample {

    // Define a simple packet
    public record ChatMessagePacket(UUID senderId, String message) implements Packet {}

    public static void main(String[] args) throws Exception {
        // Setup two instances to demonstrate broadcast
        Conduit sender = setupConduit("sender-instance");
        Conduit receiver = setupConduit("receiver-instance");

        // Register packet type on both instances
        sender.registerPacket(ChatMessagePacket.class);
        receiver.registerPacket(ChatMessagePacket.class);

        // Register handler on receiver
        receiver.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
            System.out.println("Received message from " + packet.senderId() + ": " + packet.message());
            return HandlerResult.SUCCESS;
        });

        // Start both instances
        sender.start();
        receiver.start();

        // Wait for connections to stabilize
        Thread.sleep(2000);

        // Send a broadcast message
        UUID myId = UUID.randomUUID();
        ChatMessagePacket message = new ChatMessagePacket(myId, "Hello from sender!");
        message.send();

        System.out.println("Message sent, waiting for delivery...");

        // Keep running for a bit to receive messages
        Thread.sleep(5000);

        // Cleanup
        sender.shutdown();
        receiver.shutdown();
    }

    private static Conduit setupConduit(String instanceId) {
        // Configure Redis transport
        RedisConfig config = RedisConfig.localhost();
        RedisTransport transport = new RedisTransport(config);

        // Build Conduit instance
        return Conduit.builder()
                .transport(transport)
                .instanceId(instanceId)
                .build();
    }
}
