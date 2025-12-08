package fr.traqueur.conduit.rabbitmq;

import fr.traqueur.conduit.compression.GzipCompressor;
import fr.traqueur.conduit.compression.NoOpCompressor;
import fr.traqueur.conduit.core.BaseConduitIntegrationTest;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.serialization.JsonSerializer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for Conduit with RabbitMQ transport.
 */
@Testcontainers
class ConduitRabbitMQIntegrationTest extends BaseConduitIntegrationTest {
    
    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-alpine"));
    
    @Override
    protected void setupConduitInstances() throws Exception {
        RabbitMQConfig config = new RabbitMQConfig(
            rabbitmq.getHost(),
            rabbitmq.getAmqpPort(),
            rabbitmq.getAdminUsername(),
            rabbitmq.getAdminPassword(),
            "/"
        );
        
        conduit1 = Conduit.builder()
            .transport(new RabbitMQTransport(config))
            .serializer(new JsonSerializer())
            .compressor(new NoOpCompressor())
            .instanceId("instance-1")
            .build();
        
        conduit2 = Conduit.builder()
            .transport(new RabbitMQTransport(config))
            .serializer(new JsonSerializer())
            .compressor(new NoOpCompressor())
            .instanceId("instance-2")
            .build();
    }
    
    @Override
    protected void teardownConduitInstances() {
        if (conduit1 != null) conduit1.shutdown();
        if (conduit2 != null) conduit2.shutdown();
    }

    @Override
    protected void setupConduitInstancesWithCompression() throws Exception {
        RabbitMQConfig config = new RabbitMQConfig(
                rabbitmq.getHost(),
                rabbitmq.getAmqpPort(),
                rabbitmq.getAdminUsername(),
                rabbitmq.getAdminPassword(),
                "/"
        );

        conduit1 = Conduit.builder()
                .transport(new RabbitMQTransport(config))
                .serializer(new JsonSerializer())
                .compressor(new GzipCompressor())  // Avec compression
                .instanceId("instance-1")
                .build();

        conduit2 = Conduit.builder()
                .transport(new RabbitMQTransport(config))
                .serializer(new JsonSerializer())
                .compressor(new GzipCompressor())  // Avec compression
                .instanceId("instance-2")
                .build();
    }

}