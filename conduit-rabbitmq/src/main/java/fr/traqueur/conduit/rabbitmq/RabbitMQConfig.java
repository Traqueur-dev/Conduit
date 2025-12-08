package fr.traqueur.conduit.rabbitmq;

/**
 * Configuration for RabbitMQ transport.
 *
 * @param host RabbitMQ server host
 * @param port RabbitMQ server port
 * @param username RabbitMQ username
 * @param password RabbitMQ password
 * @param virtualHost RabbitMQ virtual host
 *
 * @author Traqueur
 */
public record RabbitMQConfig(
    String host,
    int port,
    String username,
    String password,
    String virtualHost
) {
    
    /**
     * Creates a default local RabbitMQ config (localhost:5672, guest/guest, /).
     */
    public static RabbitMQConfig localhost() {
        return new RabbitMQConfig("localhost", 5672, "guest", "guest", "/");
    }
    
    /**
     * Creates a RabbitMQ config with custom host and port.
     */
    public static RabbitMQConfig of(String host, int port) {
        return new RabbitMQConfig(host, port, "guest", "guest", "/");
    }
    
    /**
     * Creates a RabbitMQ config with authentication.
     */
    public static RabbitMQConfig of(String host, int port, String username, String password) {
        return new RabbitMQConfig(host, port, username, password, "/");
    }
}