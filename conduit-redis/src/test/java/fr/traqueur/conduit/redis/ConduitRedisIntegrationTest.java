package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.core.BaseConduitIntegrationTest;
import fr.traqueur.conduit.transport.Transport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The shared suite on a real Redis.
 */
@Testcontainers
class ConduitRedisIntegrationTest extends BaseConduitIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Override
    protected Transport newTransport(String channelPrefix) {
        return RedisTransport.builder()
                .config(RedisConfig.of(redis.getHost(), redis.getFirstMappedPort()))
                .channelPrefix(channelPrefix)
                .build();
    }
}
