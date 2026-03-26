package examples.basic;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

import java.util.concurrent.CompletableFuture;

/**
 * Acknowledgment example.
 * Shows how to request and handle acknowledgments for reliable messaging.
 */
public class AcknowledgmentExample {

    // Packet that requires acknowledgment
    public record TaskPacket(String taskId, String taskType) implements AcknowledgeablePacket {}

    public static void main(String[] args) throws Exception {
        // Setup sender and receiver
        Conduit sender = setupConduit("sender");
        Conduit worker = setupConduit("worker");

        // Register packet type
        sender.registerPacket(TaskPacket.class);
        worker.registerPacket(TaskPacket.class);

        // Register handler on worker that processes task and sends ACK
        worker.registerHandler(TaskPacket.class, (packet, ackCallback) -> {
            System.out.println("Worker processing task: " + packet.taskId());

            try {
                // Simulate task processing
                Thread.sleep(1000);
                String taskResult = processTask(packet);

                // Send success acknowledgment
                if (ackCallback != null) {
                    AckResponse ack = AckResponse.success(packet.taskId(), taskResult);
                    ackCallback.accept(ack);
                }

            } catch (Exception e) {
                // Send failure acknowledgment
                if (ackCallback != null) {
                    AckResponse ack = AckResponse.failure(packet.taskId(), e.getMessage());
                    ackCallback.accept(ack);
                }
            }
            return null;
        });

        // Start both instances
        sender.start();
        worker.start();

        Thread.sleep(2000);

        // Send task with acknowledgment request
        System.out.println("Sending task and waiting for acknowledgment...");
        TaskPacket task = new TaskPacket("task-123", "data-processing");

        CompletableFuture<AckResponse> ackFuture = task.sendWithAck(10000); // 10 second timeout

        // Wait for acknowledgment
        AckResponse ack = ackFuture.get();

        if (ack.success()) {
            System.out.println("Task completed successfully: " + ack.message());
        } else {
            System.err.println("Task failed: " + ack.message());
        }

        // Cleanup
        sender.shutdown();
        worker.shutdown();
    }

    private static String processTask(TaskPacket task) throws InterruptedException {
        // Simulate task processing
        System.out.println("Processing " + task.taskType() + " task...");
        Thread.sleep(500);
        return "Task " + task.taskId() + " completed successfully";
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
