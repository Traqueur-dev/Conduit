package examples.advanced;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.packet.TargetableAcknowledgeablePacket;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load balancing example.
 * Demonstrates distributing work across multiple worker instances.
 */
public class LoadBalancingExample {

    public record WorkTaskPacket(int taskId, String workload) implements TargetableAcknowledgeablePacket {}

    private static final AtomicInteger taskCounter = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("Load Balancing Example\n");

        // Create coordinator
        Conduit coordinator = setupConduit("coordinator");

        // Create 3 worker instances
        List<Conduit> workers = new ArrayList<>();
        List<String> workerIds = List.of("worker-1", "worker-2", "worker-3");

        for (String workerId : workerIds) {
            Conduit worker = setupConduit(workerId);
            workers.add(worker);

            // Register packet and handler
            worker.registerPacket(WorkTaskPacket.class);
            worker.registerHandler(WorkTaskPacket.class, (packet, ackCallback) -> {
                try {
                    // Simulate work processing
                    System.out.println(workerId + " processing task " + packet.taskId());
                    Thread.sleep(500 + new Random().nextInt(1000));

                    String result = "Task " + packet.taskId() + " completed by " + workerId;
                    if (ackCallback != null) {
                        ackCallback.accept(AckResponse.success(String.valueOf(packet.taskId()), result));
                    }
                } catch (Exception e) {
                    if (ackCallback != null) {
                        ackCallback.accept(AckResponse.failure(String.valueOf(packet.taskId()), e.getMessage()));
                    }
                }
                return null;
            });

            worker.start();
        }

        // Setup coordinator
        coordinator.registerPacket(WorkTaskPacket.class);
        coordinator.start();

        Thread.sleep(2000);

        // Distribute 10 tasks across workers using round-robin
        System.out.println("Distributing 10 tasks across " + workers.size() + " workers...\n");

        List<CompletableFuture<AckResponse>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            int taskId = taskCounter.incrementAndGet();
            String targetWorker = workerIds.get(i % workerIds.size());

            System.out.println("Assigning task " + taskId + " to " + targetWorker);

            WorkTaskPacket task = new WorkTaskPacket(taskId, "Sample workload " + taskId);
            CompletableFuture<AckResponse> future = task.sendWithAck(targetWorker, 10000);

            futures.add(future);
        }

        // Wait for all tasks to complete
        System.out.println("\nWaiting for all tasks to complete...\n");

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Print results
        int successCount = 0;
        for (CompletableFuture<AckResponse> future : futures) {
            AckResponse ack = future.get();
            if (ack.success()) {
                System.out.println("✓ " + ack.message());
                successCount++;
            } else {
                System.out.println("✗ Failed: " + ack.message());
            }
        }

        System.out.println("\nCompleted " + successCount + "/" + futures.size() + " tasks successfully");

        // Cleanup
        coordinator.shutdown();
        workers.forEach(Conduit::shutdown);
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
