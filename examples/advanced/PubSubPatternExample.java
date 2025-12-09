package examples.advanced;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.core.PacketMeta;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Publisher-Subscriber pattern example.
 * Demonstrates a real-world event-driven architecture with multiple subscribers.
 */
public class PubSubPatternExample {

    // Event packets on different channels
    @PacketMeta(channel = "orders")
    public record OrderCreatedEvent(String orderId, double amount, Instant timestamp) implements Packet {}

    @PacketMeta(channel = "orders")
    public record OrderShippedEvent(String orderId, String trackingNumber, Instant timestamp) implements Packet {}

    @PacketMeta(channel = "inventory")
    public record InventoryUpdatedEvent(String productId, int quantity, Instant timestamp) implements Packet {}

    @PacketMeta(channel = "notifications")
    public record NotificationEvent(String userId, String message, Instant timestamp) implements Packet {}

    public static void main(String[] args) throws Exception {
        System.out.println("Publisher-Subscriber Pattern Example\n");

        // Create publisher
        Conduit publisher = setupConduit("publisher");

        // Create specialized subscribers
        Conduit orderProcessor = setupConduit("order-processor");
        Conduit inventoryManager = setupConduit("inventory-manager");
        Conduit notificationService = setupConduit("notification-service");
        Conduit analyticsService = setupConduit("analytics-service");

        // Register all packet types on publisher
        registerAllPackets(publisher);

        // Order processor - subscribes to order events
        registerAllPackets(orderProcessor);
        orderProcessor.registerHandler(OrderCreatedEvent.class, (packet, ackCallback) -> {
            System.out.println("[Order Processor] New order: " + packet.orderId() + " ($" + packet.amount() + ")");
            return HandlerResult.SUCCESS;
        });
        orderProcessor.registerHandler(OrderShippedEvent.class, (packet, ackCallback) -> {
            System.out.println("[Order Processor] Order shipped: " + packet.orderId() +
                    " (Tracking: " + packet.trackingNumber() + ")");
            return HandlerResult.SUCCESS;
        });

        // Inventory manager - subscribes to inventory events
        registerAllPackets(inventoryManager);
        inventoryManager.registerHandler(InventoryUpdatedEvent.class, (packet, ackCallback) -> {
            System.out.println("[Inventory Manager] Stock updated: Product " + packet.productId() +
                    " = " + packet.quantity() + " units");
            return HandlerResult.SUCCESS;
        });

        // Notification service - subscribes to notification events
        registerAllPackets(notificationService);
        notificationService.registerHandler(NotificationEvent.class, (packet, ackCallback) -> {
            System.out.println("[Notification Service] Sending to user " + packet.userId() +
                    ": " + packet.message());
            return HandlerResult.SUCCESS;
        });

        // Analytics service - subscribes to ALL events
        registerAllPackets(analyticsService);
        analyticsService.registerHandler(OrderCreatedEvent.class, (packet, ackCallback) -> {
            System.out.println("[Analytics] Logged order event");
            return HandlerResult.SUCCESS;
        });
        analyticsService.registerHandler(OrderShippedEvent.class, (packet, ackCallback) -> {
            System.out.println("[Analytics] Logged shipping event");
            return HandlerResult.SUCCESS;
        });
        analyticsService.registerHandler(InventoryUpdatedEvent.class, (packet, ackCallback) -> {
            System.out.println("[Analytics] Logged inventory event");
            return HandlerResult.SUCCESS;
        });
        analyticsService.registerHandler(NotificationEvent.class, (packet, ackCallback) -> {
            System.out.println("[Analytics] Logged notification event");
            return HandlerResult.SUCCESS;
        });

        // Start all services
        List<Conduit> services = List.of(
                publisher, orderProcessor, inventoryManager,
                notificationService, analyticsService
        );
        for (Conduit service : services) {
            service.start();
        }

        Thread.sleep(2000);

        // Simulate business events
        System.out.println("Publishing business events...\n");

        // Order flow
        System.out.println("--- Order Flow ---");
        new OrderCreatedEvent("ORD-001", 149.99, Instant.now()).send();
        Thread.sleep(500);

        new InventoryUpdatedEvent("PROD-123", 45, Instant.now()).send();
        Thread.sleep(500);

        new NotificationEvent("user-456", "Your order ORD-001 has been confirmed", Instant.now()).send();
        Thread.sleep(1000);

        new OrderShippedEvent("ORD-001", "TRACK-XYZ789", Instant.now()).send();
        Thread.sleep(500);

        new NotificationEvent("user-456", "Your order ORD-001 has been shipped", Instant.now()).send();
        Thread.sleep(1000);

        System.out.println("\n--- Additional Events ---");
        new InventoryUpdatedEvent("PROD-789", 12, Instant.now()).send();
        Thread.sleep(500);

        new OrderCreatedEvent("ORD-002", 79.99, Instant.now()).send();
        Thread.sleep(1000);

        System.out.println("\nAll events published and processed!");

        // Cleanup
        services.forEach(Conduit::shutdown);
    }

    private static void registerAllPackets(Conduit conduit) {
        conduit.registerPacket(OrderCreatedEvent.class);
        conduit.registerPacket(OrderShippedEvent.class);
        conduit.registerPacket(InventoryUpdatedEvent.class);
        conduit.registerPacket(NotificationEvent.class);
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
