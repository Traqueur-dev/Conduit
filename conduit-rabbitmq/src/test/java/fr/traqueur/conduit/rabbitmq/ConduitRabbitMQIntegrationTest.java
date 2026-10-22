package fr.traqueur.conduit.rabbitmq;

import fr.traqueur.conduit.core.BaseConduitIntegrationTest;
import fr.traqueur.conduit.transport.Transport;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The shared suite on a real RabbitMQ.
 */
@Testcontainers
class ConduitRabbitMQIntegrationTest extends BaseConduitIntegrationTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-alpine"));

    @Override
    protected Transport newTransport() {
        return new RabbitMQTransport(new RabbitMQConfig(
                rabbitmq.getHost(), rabbitmq.getAmqpPort(), rabbitmq.getAdminUsername(), rabbitmq.getAdminPassword(), "/"));
    }
}
