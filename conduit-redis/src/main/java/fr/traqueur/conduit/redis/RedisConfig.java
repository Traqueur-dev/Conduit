package fr.traqueur.conduit.redis;

/**
 * Configuration for Redis transport.
 *
 * @param host Redis server host
 * @param port Redis server port
 * @param password Redis password (null if no auth)
 * @param database Redis database number (0-15)
 * @param ssl whether to speak TLS to that port ({@code rediss://})
 *
 * @author Traqueur
 */
public record RedisConfig(
    String host,
    int port,
    String password,
    int database,
    boolean ssl
) {

    /**
     * Without TLS — the four-argument shape this record had before {@code ssl} existed.
     *
     * <p>Kept so the change is compatible at the SOURCE and at the BINARY level: the canonical
     * four-argument constructor's descriptor still exists, so callers compiled against 1.1.4 keep
     * linking. Every existing caller reaches TLS by adding one argument, not by being rewritten.
     */
    public RedisConfig(String host, int port, String password, int database) {
        this(host, port, password, database, false);
    }
    
    /**
     * Creates a default local Redis config (localhost:6379, no password, db 0).
     *
     * @return a default local Redis configuration
     */
    public static RedisConfig localhost() {
        return new RedisConfig("localhost", 6379, null, 0, false);
    }

    /**
     * Creates a Redis config with custom host and port.
     *
     * @param host the Redis server host
     * @param port the Redis server port
     * @return a Redis configuration
     */
    public static RedisConfig of(String host, int port) {
        return new RedisConfig(host, port, null, 0, false);
    }

    /**
     * Creates a Redis config with authentication.
     *
     * @param host the Redis server host
     * @param port the Redis server port
     * @param password the Redis password
     * @return a Redis configuration with authentication
     */
    public static RedisConfig of(String host, int port, String password) {
        return new RedisConfig(host, port, password, 0, false);
    }

    /**
     * Creates a Redis config with authentication, over TLS.
     *
     * @param host the Redis server host
     * @param port the Redis server port
     * @param password the Redis password
     * @param ssl whether to speak TLS
     * @return a Redis configuration
     */
    public static RedisConfig of(String host, int port, String password, boolean ssl) {
        return new RedisConfig(host, port, password, 0, ssl);
    }

    /**
     * Gets the Redis URI for Lettuce connection.
     * Format: redis://[:password@]host:port/database, or rediss:// when {@link #ssl()}.
     *
     * <p>The scheme follows {@code ssl} rather than being hardcoded. Nothing in this repository
     * calls this method — {@link RedisTransport} builds its {@code RedisURI} from the components —
     * and that is precisely why it mattered: a dead helper that quietly builds a CLEARTEXT URI is
     * the kind of thing someone picks up one day believing it works.
     *
     * @return the Redis URI string
     */
    public String toUri() {
        StringBuilder uri = new StringBuilder(ssl ? "rediss://" : "redis://");
        
        if (password != null && !password.isEmpty()) {
            uri.append(":").append(password).append("@");
        }
        
        uri.append(host).append(":").append(port);
        uri.append("/").append(database);
        
        return uri.toString();
    }
}