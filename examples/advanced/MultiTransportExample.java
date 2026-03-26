package examples.advanced;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.rabbitmq.RabbitMQConfig;
import fr.traqueur.conduit.rabbitmq.RabbitMQTransport;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

/**
 * Multi-transport example.
 * Shows how to use both Redis and RabbitMQ transports in the same application.
 */
public class MultiTransportExample {

    public record EventPacket(String eventType, String data) implements Packet {}

    public static void main(String[] args) throws Exception {
        System.out.println("Starting multi-transport example...\n");

        // Instance 1: Using Redis
        Conduit redisInstance = setupRedisConduit("redis-instance");

        // Instance 2: Using RabbitMQ
        Conduit rabbitInstance = setupRabbitMQConduit("rabbitmq-instance");

        // Register packet type on both
        redisInstance.registerPacket(EventPacket.class);
        rabbitInstance.registerPacket(EventPacket.class);

        // Register handler on Redis instance
        redisInstance.registerHandler(EventPacket.class, (packet, ackCallback) ->
            System.out.println("[Redis Instance] Received: " + packet.eventType() + " - " + packet.data()));

        // Register handler on RabbitMQ instance
        rabbitInstance.registerHandler(EventPacket.class, (packet, ackCallback) ->
            System.out.println("[RabbitMQ Instance] Received: " + packet.eventType() + " - " + packet.data()));

        // Start both
        redisInstance.start();
        rabbitInstance.start();

        Thread.sleep(2000);

        // Send from Redis instance
        System.out.println("Sending from Redis instance...");
        new EventPacket("REDIS_EVENT", "Message from Redis").send();
        Thread.sleep(1000);

        // Send from RabbitMQ instance
        System.out.println("Sending from RabbitMQ instance...");
        new EventPacket("RABBITMQ_EVENT", "Message from RabbitMQ").send();
        Thread.sleep(2000);

        System.out.println("\nNote: Instances on different transports don't communicate with each other.");
        System.out.println("Each transport creates its own isolated messaging network.");

        // Cleanup
        redisInstance.shutdown();
        rabbitInstance.shutdown();
    }

    private static Conduit setupRedisConduit(String instanceId) {
        RedisConfig config = RedisConfig.localhost();
        RedisTransport transport = new RedisTransport(config);

        return Conduit.builder()
                .transport(transport)
                .instanceId(instanceId)
                .defaultChannel("multi-transport-example")
                .build();
    }

    private static Conduit setupRabbitMQConduit(String instanceId) {
        RabbitMQConfig config = RabbitMQConfig.localhost();
        RabbitMQTransport transport = new RabbitMQTransport(config);

        return Conduit.builder()
                .transport(transport)
                .instanceId(instanceId)
                .defaultChannel("multi-transport-example")
                .build();
    }
}
