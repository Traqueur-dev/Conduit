package fr.traqueur.conduit.redis;

import java.time.Duration;

/**
 * Connection settings for a {@link RedisTransport} that opens its own client. To reuse a client
 * the application already has, pass it to {@link RedisTransport#RedisTransport(io.lettuce.core.RedisClient)}
 * instead; for anything this record does not cover, pass a {@link io.lettuce.core.RedisURI}.
 *
 * @param host       Redis server host
 * @param port       Redis server port
 * @param username   ACL user name, or null for the {@code default} user
 * @param password   password, or null for no authentication
 * @param database   database number
 * @param ssl        whether to speak TLS to that port ({@code rediss://})
 * @param verifyPeer whether to verify the server's certificate and hostname. {@code true} is the
 *                   only safe default; {@code false} keeps the wire ENCRYPTED and stops
 *                   AUTHENTICATING the server, which is a deliberate trade, not a fallback
 * @param timeout    command timeout, or null for Lettuce's default (60 seconds)
 *
 * @author Traqueur
 */
public record RedisConfig(
    String host,
    int port,
    String username,
    String password,
    int database,
    boolean ssl,
    boolean verifyPeer,
    Duration timeout
) {

    /**
     * Local Redis: {@code localhost:6379}, no authentication, database 0.
     *
     * @return the configuration
     */
    public static RedisConfig localhost() {
        return builder("localhost", 6379).build();
    }

    /**
     * @param host the host
     * @param port the port
     * @return a configuration without authentication nor TLS
     */
    public static RedisConfig of(String host, int port) {
        return builder(host, port).build();
    }

    /**
     * @param host the host
     * @param port the port
     * @return a builder starting from no authentication, database 0, no TLS
     */
    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    /** The password never appears here. */
    @Override
    public String toString() {
        return "RedisConfig[" + (ssl ? "rediss://" : "redis://") + (username != null ? username + "@" : "")
                + host + ":" + port + "/" + database + (password != null ? ", password=***" : "")
                + (ssl && !verifyPeer ? ", peer NOT verified" : "") + (timeout != null ? ", timeout=" + timeout : "") + "]";
    }

    /**
     * Builder of {@link RedisConfig}.
     */
    public static final class Builder {
        private final String host;
        private final int port;
        private String username;
        private String password;
        private int database;
        private boolean ssl;
        private boolean verifyPeer = true;
        private Duration timeout;

        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /**
         * @param username ACL user name
         * @param password its password
         * @return this builder
         */
        public Builder credentials(String username, String password) {
            this.username = username;
            this.password = password;
            return this;
        }

        /**
         * @param password password of the {@code default} user
         * @return this builder
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * @param database database number
         * @return this builder
         */
        public Builder database(int database) {
            this.database = database;
            return this;
        }

        /**
         * Speaks TLS, verifying the server.
         *
         * @return this builder
         */
        public Builder ssl() {
            this.ssl = true;
            return this;
        }

        /**
         * Speaks TLS WITHOUT verifying the server: encrypted, not authenticated. For a link whose
         * endpoints are known to each other by other means; whoever calls it should be able to say why.
         *
         * @return this builder
         */
        public Builder sslWithoutPeerVerification() {
            this.ssl = true;
            this.verifyPeer = false;
            return this;
        }

        /**
         * @param timeout command timeout
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * @return the configuration
         */
        public RedisConfig build() {
            return new RedisConfig(host, port, username, password, database, ssl, verifyPeer, timeout);
        }
    }
}
