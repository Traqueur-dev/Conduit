package examples.advanced;

import fr.traqueur.conduit.compression.GzipCompressor;
import fr.traqueur.conduit.compression.NoOpCompressor;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

/**
 * Compression example.
 * Demonstrates the difference between compressed and uncompressed message transfer.
 */
public class CompressionExample {

    public record LargeDataPacket(String data) implements Packet {}

    public static void main(String[] args) throws Exception {
        System.out.println("Compression Example\n");

        // Create large payload (10KB of data)
        String largePayload = "A".repeat(10000);

        // Test without compression
        System.out.println("=== Without Compression ===");
        testTransfer(false, largePayload);

        Thread.sleep(2000);

        // Test with compression
        System.out.println("\n=== With Gzip Compression ===");
        testTransfer(true, largePayload);

        System.out.println("\nConclusion:");
        System.out.println("- Compression reduces network bandwidth for large payloads");
        System.out.println("- Use compression for payloads > 1KB");
        System.out.println("- Trade-off: CPU time for network bandwidth");
    }

    private static void testTransfer(boolean useCompression, String payload) throws Exception {
        Conduit sender = setupConduit("sender", useCompression);
        Conduit receiver = setupConduit("receiver", useCompression);

        sender.registerPacket(LargeDataPacket.class);
        receiver.registerPacket(LargeDataPacket.class);

        receiver.registerHandler(LargeDataPacket.class, (packet, ackCallback) ->
            System.out.println("Received payload of length: " + packet.data().length()));

        sender.start();
        receiver.start();

        Thread.sleep(2000);

        // Measure transfer time
        long startTime = System.currentTimeMillis();
        new LargeDataPacket(payload).send();
        Thread.sleep(1000);
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("Transfer completed in " + duration + "ms");
        System.out.println("Payload size: " + payload.length() + " bytes");

        sender.shutdown();
        receiver.shutdown();
    }

    private static Conduit setupConduit(String instanceId, boolean useCompression) {
        RedisConfig config = RedisConfig.localhost();
        RedisTransport transport = new RedisTransport(config);

        return Conduit.builder()
                .transport(transport)
                .instanceId(instanceId)
                .compressor(useCompression ? new GzipCompressor() : new NoOpCompressor())
                .build();
    }
}
