package fr.traqueur.conduit.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import fr.traqueur.conduit.core.AckResponse;
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.core.PacketMeta;
import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
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
 * What only RabbitMQ can say, mirroring {@code RedisTransportTest}: a provided connection,
 * permission refusals, the prefix on exchange and queue names.
 */
@Testcontainers
class RabbitMQTransportTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-alpine"));

    @PacketMeta(channel = "allowed-events", wire = "test.allowed.v1")
    public record AllowedPacket(String value) implements AcknowledgeablePacket {}

    @PacketMeta(channel = "forbidden-events", wire = "test.forbidden.v1")
    public record ForbiddenPacket(String value) implements Packet {}

    public record Hello(String value) implements Packet {}

    private final List<Conduit> conduits = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createRestrictedUser() throws Exception {
        // Everything under the "lobby:" prefix, except what concerns forbidden-events.
        String pattern = "^lobby:(?!.*forbidden-events).*";
        run("rabbitmqctl", "add_user", "lobby", "lobby-pw");
        run("rabbitmqctl", "set_permissions", "-p", "/", "lobby", pattern, pattern, pattern);
    }

    private static void run(String... command) throws Exception {
        ExecResult result = rabbitmq.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    @AfterEach
    void shutdown() {
        conduits.forEach(Conduit::shutdown);
    }

    private static ConnectionFactory factory(String user, String password) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbitmq.getHost());
        factory.setPort(rabbitmq.getAmqpPort());
        factory.setUsername(user);
        factory.setPassword(password);
        return factory;
    }

    private static ConnectionFactory admin() {
        return factory(rabbitmq.getAdminUsername(), rabbitmq.getAdminPassword());
    }

    private Conduit conduit(RabbitMQTransport transport, String id, String defaultChannel) {
        Conduit c = Conduit.builder().transport(transport).instanceId(id).defaultChannel(defaultChannel).unregistered().build();
        conduits.add(c);
        return c;
    }

    @Test
    @DisplayName("A provided Connection survives the transport's close(), and serves another transport")
    void providedConnectionSurvivesClose() throws Exception {
        try (Connection application = admin().newConnection()) {
            Conduit first = conduit(new RabbitMQTransport(application), "first", "provided-test");
            first.registerPacket(Hello.class);
            first.start();
            first.shutdown();

            assertThat(application.isOpen()).isTrue();
            try (Channel ch = application.createChannel()) {
                assertThat(ch.isOpen()).isTrue();
            }

            Conduit a = conduit(RabbitMQTransport.builder().connection(application).build(), "a", "provided-test");
            Conduit b = conduit(RabbitMQTransport.builder().connection(application).build(), "b", "provided-test");
            for (Conduit c : List.of(a, b)) {
                c.registerPacket(Hello.class);
                c.start();
            }
            AtomicInteger got = new AtomicInteger();
            b.registerHandler(Hello.class, (p, ack) -> { got.incrementAndGet(); return null; });
            a.send(new Hello("x")).get(10, TimeUnit.SECONDS);
            await().atMost(10, TimeUnit.SECONDS).until(() -> got.get() == 1);

            a.shutdown();
            b.shutdown();
            assertThat(application.isOpen()).isTrue();
        }
    }

    @Test
    @DisplayName("A permission refusal fails the send future and the subscription future, and nothing else")
    void permissionRefusalFailsTheFuture() throws Exception {
        RabbitMQTransport transport = RabbitMQTransport.builder()
                .connectionFactory(factory("lobby", "lobby-pw"))
                .channelPrefix("lobby:")
                .build();
        Conduit lobby = conduit(transport, "lobby-1", "packets");
        lobby.registerPacket(AllowedPacket.class);
        lobby.start();
        Conduit peer = conduit(RabbitMQTransport.builder().connectionFactory(factory("lobby", "lobby-pw"))
                .channelPrefix("lobby:").build(), "lobby-2", "packets");
        peer.registerPacket(AllowedPacket.class);
        peer.start();
        peer.registerHandler(AllowedPacket.class, (p, ack) -> { ack.accept(AckResponse.success("x", "ok")); return null; });

        CompletableFuture<Void> subscription = lobby.registerPacket(ForbiddenPacket.class);
        assertThatThrownBy(() -> subscription.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("ACCESS_REFUSED");

        assertThatThrownBy(() -> lobby.send(new ForbiddenPacket("x")).get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasStackTraceContaining("ACCESS_REFUSED");
        assertThat(lobby.metrics().sendFailures()).isEqualTo(1);

        // The refusals closed nothing else: what the permissions allow still works, ACK included.
        assertThat(lobby.sendWithAck(new AllowedPacket("ok"), 10_000).get(15, TimeUnit.SECONDS).message()).isEqualTo("ok");
    }

    @Test
    @DisplayName("A user restricted to one prefix cannot use another")
    void prefixIsAPermissionBoundary() throws Exception {
        RabbitMQTransport outside = RabbitMQTransport.builder()
                .connectionFactory(factory("lobby", "lobby-pw"))
                .channelPrefix("staging:")
                .build();
        Conduit stray = conduit(outside, "stray", "packets");
        assertThatThrownBy(stray::start).hasStackTraceContaining("ACCESS_REFUSED");
    }

    @Test
    @DisplayName("Exchanges and queues carry the prefix")
    void prefixIsOnEveryName() throws Exception {
        Conduit prod = conduit(RabbitMQTransport.builder().connectionFactory(admin()).channelPrefix("prod:").build(),
                "p1", "prefix-test");
        prod.registerPacket(AllowedPacket.class);
        prod.start();

        try (Connection c = admin().newConnection(); Channel ch = c.createChannel()) {
            ch.exchangeDeclarePassive("prod:conduit.broadcast.prefix-test");
            ch.exchangeDeclarePassive("prod:conduit.direct.prefix-test");
            ch.exchangeDeclarePassive("prod:conduit.direct.prefix-test:ack");
            ch.exchangeDeclarePassive("prod:conduit.broadcast.allowed-events");
            ch.queueDeclarePassive("prod:conduit.unicast.prefix-test.p1");
        }
        try (Connection c = admin().newConnection()) {
            // Not in try-with-resources: the refusal closes the channel, and closing it again would throw.
            Channel ch = c.createChannel();
            assertThatThrownBy(() -> ch.exchangeDeclarePassive("conduit.broadcast.prefix-test"))
                    .hasStackTraceContaining("NOT_FOUND");
        }
    }
}
