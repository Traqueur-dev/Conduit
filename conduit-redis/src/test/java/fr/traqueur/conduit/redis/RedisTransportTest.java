package fr.traqueur.conduit.redis;

import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.core.PacketMeta;
import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.packet.Packet;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * What only Redis can say: a provided client, ACL refusals, the channel prefix on the wire.
 */
@Testcontainers
class RedisTransportTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @PacketMeta(channel = "allowed:events", wire = "test.allowed.v1")
    public record AllowedPacket(String value) implements AcknowledgeablePacket {}

    @PacketMeta(channel = "forbidden:events", wire = "test.forbidden.v1")
    public record ForbiddenPacket(String value) implements Packet {}

    public record Hello(String value) implements Packet {}

    private final List<Conduit> conduits = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createAclUser() {
        RedisClient admin = RedisClient.create(uri());
        try (StatefulRedisConnection<String, String> c = admin.connect()) {
            // May publish and subscribe on allowed:* only.
            c.sync().aclSetuser("lobby", io.lettuce.core.AclSetuserArgs.Builder
                    .on().addPassword("lobby-pw").allKeys().allCommands().channelPattern("allowed:*"));
        } finally {
            admin.shutdown();
        }
    }

    @AfterEach
    void shutdown() {
        conduits.forEach(Conduit::shutdown);
    }

    private static RedisURI uri() {
        return RedisURI.create(redis.getHost(), redis.getFirstMappedPort());
    }

    private Conduit conduit(RedisTransport transport, String id, String defaultChannel) {
        Conduit c = Conduit.builder().transport(transport).instanceId(id).defaultChannel(defaultChannel).unregistered().build();
        conduits.add(c);
        return c;
    }

    @Test
    @DisplayName("A provided RedisClient survives the transport's close(), and serves another transport")
    void providedClientSurvivesClose() throws Exception {
        RedisClient application = RedisClient.create(uri());
        try {
            Conduit first = conduit(new RedisTransport(application), "first", "conduit-test");
            first.registerPacket(Hello.class);
            first.start();
            first.shutdown();

            try (StatefulRedisConnection<String, String> c = application.connect()) {
                assertThat(c.sync().ping()).isEqualTo("PONG");
            }

            Conduit a = conduit(RedisTransport.builder().client(application).build(), "a", "conduit-test");
            Conduit b = conduit(RedisTransport.builder().client(application).build(), "b", "conduit-test");
            for (Conduit c : List.of(a, b)) {
                c.registerPacket(Hello.class);
                c.start();
            }
            AtomicInteger got = new AtomicInteger();
            b.registerHandler(Hello.class, (p, ack) -> { got.incrementAndGet(); return null; });
            a.send(new Hello("x")).get(10, TimeUnit.SECONDS);
            await().atMost(10, TimeUnit.SECONDS).until(() -> got.get() == 1);
        } finally {
            application.shutdown();
        }
    }

    @Test
    @DisplayName("An ACL refusal (NOPERM) fails the send future, and the subscription future")
    void aclRefusalFailsTheFuture() throws Exception {
        RedisTransport transport = new RedisTransport(RedisConfig.builder(redis.getHost(), redis.getFirstMappedPort())
                .credentials("lobby", "lobby-pw").build());
        Conduit lobby = conduit(transport, "lobby-1", "allowed:packets");
        lobby.registerPacket(AllowedPacket.class);
        lobby.start();

        CompletableFuture<Void> subscription = lobby.registerPacket(ForbiddenPacket.class);
        assertThatThrownBy(() -> subscription.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RedisCommandExecutionException.class)
                .hasMessageContaining("NOPERM");

        assertThatThrownBy(() -> lobby.send(new ForbiddenPacket("x")).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RedisCommandExecutionException.class)
                .hasMessageContaining("NOPERM");
        assertThat(lobby.metrics().sendFailures()).isEqualTo(1);

        // What the ACL allows still works, ACK included (reply channel allowed:packets:ack:<id>).
        lobby.send(new AllowedPacket("ok")).get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("The prefix is on every physical channel, and isolates environments")
    void prefixIsolates() throws Exception {
        Conduit prod1 = conduit(RedisTransport.builder().uri(uri()).channelPrefix("prod:").build(), "p1", "conduit-test");
        Conduit prod2 = conduit(RedisTransport.builder().uri(uri()).channelPrefix("prod:").build(), "p2", "conduit-test");
        Conduit staging = conduit(RedisTransport.builder().uri(uri()).channelPrefix("staging:").build(), "s1", "conduit-test");
        for (Conduit c : List.of(prod1, prod2, staging)) {
            c.registerPacket(AllowedPacket.class);
            c.start();
        }
        AtomicInteger stagingGot = new AtomicInteger();
        staging.registerHandler(AllowedPacket.class, (p, ack) -> { stagingGot.incrementAndGet(); return null; });
        prod2.registerHandler(AllowedPacket.class, (p, ack) -> { ack.accept(AckResponse.success("x")); return null; });

        RedisClient spy = RedisClient.create(uri());
        try (StatefulRedisPubSubConnection<String, String> pubSub = spy.connectPubSub()) {
            List<String> seen = new CopyOnWriteArrayList<>();
            pubSub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String pattern, String channel, String message) {
                    seen.add(channel);
                }
            });
            pubSub.sync().psubscribe("*");

            assertThat(prod1.sendWithAck(new AllowedPacket("x"), 10_000).get(15, TimeUnit.SECONDS).success()).isTrue();

            await().atMost(10, TimeUnit.SECONDS).until(() -> seen.contains("prod:conduit-test:ack:p1"));
            assertThat(seen).contains("prod:allowed:events").allMatch(ch -> ch.startsWith("prod:"));
            Thread.sleep(300);
            assertThat(stagingGot.get()).isZero();
        } finally {
            spy.shutdown();
        }
    }
}
