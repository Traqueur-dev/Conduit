package fr.traqueur.conduit.redis;

/**
 * Configuration for Redis transport.
 *
 * @param host Redis server host
 * @param port Redis server port
 * @param password Redis password (null if no auth)
 * @param database Redis database number (0-15)
 *
 * @author Traqueur
 */
public record RedisConfig(
    String host,
    int port,
    String password,
    int database
) {
    
    /**
     * Creates a default local Redis config (localhost:6379, no password, db 0).
     */
    public static RedisConfig localhost() {
        return new RedisConfig("localhost", 6379, null, 0);
    }
    
    /**
     * Creates a Redis config with custom host and port.
     */
    public static RedisConfig of(String host, int port) {
        return new RedisConfig(host, port, null, 0);
    }
    
    /**
     * Creates a Redis config with authentication.
     */
    public static RedisConfig of(String host, int port, String password) {
        return new RedisConfig(host, port, password, 0);
    }
    
    /**
     * Gets the Redis URI for Lettuce connection.
     */
    public String toUri() {
        StringBuilder uri = new StringBuilder("redis://");
        
        if (password != null && !password.isEmpty()) {
            uri.append(":").append(password).append("@");
        }
        
        uri.append(host).append(":").append(port);
        uri.append("/").append(database);
        
        return uri.toString();
    }
}