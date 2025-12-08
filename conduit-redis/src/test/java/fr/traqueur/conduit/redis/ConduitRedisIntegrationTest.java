package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.compression.GzipCompressor;
import fr.traqueur.conduit.compression.NoOpCompressor;
import fr.traqueur.conduit.core.BaseConduitIntegrationTest;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.serialization.JsonSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Conduit with Redis transport.
 */
@Testcontainers
class ConduitRedisIntegrationTest extends BaseConduitIntegrationTest {
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    
    @Override
    protected void setupConduitInstances() throws Exception {
        RedisConfig config = new RedisConfig(
            redis.getHost(),
            redis.getFirstMappedPort(),
            null,
            0
        );
        
        conduit1 = Conduit.builder()
            .transport(new RedisTransport(config))
            .serializer(new JsonSerializer())
            .compressor(new NoOpCompressor())
            .instanceId("instance-1")
            .defaultChannel("conduit-test")
            .build();
        
        conduit2 = Conduit.builder()
            .transport(new RedisTransport(config))
            .serializer(new JsonSerializer())
            .compressor(new NoOpCompressor())
            .instanceId("instance-2")
            .defaultChannel("conduit-test")
            .build();
    }
    
    @Override
    protected void teardownConduitInstances() {
        if (conduit1 != null) conduit1.shutdown();
        if (conduit2 != null) conduit2.shutdown();
    }

    @Override
    protected void setupConduitInstancesWithCompression() throws Exception {
        RedisConfig config = new RedisConfig(
                redis.getHost(),
                redis.getFirstMappedPort(),
                null,
                0
        );

        conduit1 = Conduit.builder()
                .transport(new RedisTransport(config))
                .serializer(new JsonSerializer())
                .compressor(new GzipCompressor())  // Avec compression
                .instanceId("instance-1")
                .build();

        conduit2 = Conduit.builder()
                .transport(new RedisTransport(config))
                .serializer(new JsonSerializer())
                .compressor(new GzipCompressor())  // Avec compression
                .instanceId("instance-2")
                .build();
    }

}