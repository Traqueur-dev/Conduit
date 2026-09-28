package fr.traqueur.conduit.redis;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TLS is a property of the PORT, so it has to reach the connection — and nothing else here can say
 * whether it did.
 *
 * <p>These are not integration tests on purpose. Proving the handshake needs a Redis published with
 * {@code tls-port} and a certificate, which is heavier than the change it would guard. What can
 * break silently is narrower and fully testable: the flag not travelling from the config to the URI.
 * A cleartext client on a TLS port does not degrade, it simply never connects, and the callers that
 * share that port go quiet together.
 */
class RedisConfigSslTest {

    @Test
    @DisplayName("ssl reaches the URI the transport will dial, and false stays cleartext")
    void sslReachesTheUri() {
        // RedisTransport.uriOf, not a copy of it: a copy passes even when the transport forgets.
        assertThat(RedisTransport.uriOf(new RedisConfig("h", 6380, "p", 0, true)).isSsl()).isTrue();
        assertThat(RedisTransport.uriOf(new RedisConfig("h", 6380, "p", 0, false)).isSsl()).isFalse();
        // And the rest of the URI still describes the config — the extraction changed nothing else.
        RedisURI uri = RedisTransport.uriOf(new RedisConfig("host", 6380, "secret", 3, true));
        assertThat(uri.getHost()).isEqualTo("host");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(3);
    }

    @Test
    @DisplayName("the four-argument shape still exists, and it is cleartext")
    void theFourArgumentShapeIsUnchanged() {
        // The point of keeping it is compatibility: a caller compiled against 1.1.4 links against
        // this descriptor. If it ever disappears, every consumer breaks at RUNTIME, not at compile
        // time — the worst of the two.
        RedisConfig legacy = new RedisConfig("h", 6379, null, 0);
        assertThat(legacy.ssl()).isFalse();
        assertThat(RedisConfig.localhost().ssl()).isFalse();
        assertThat(RedisConfig.of("h", 6379).ssl()).isFalse();
        assertThat(RedisConfig.of("h", 6379, "p").ssl()).isFalse();
        assertThat(RedisConfig.of("h", 6379, "p", true).ssl()).isTrue();
    }

    @Test
    @DisplayName("verifyPeer is TRUE by default, and only an explicit call relaxes it")
    void verifyPeerDefaultsToTrue() {
        // The point of the default: every shape that existed before this field asked for the STRONG
        // form. A five-argument call silently becoming unauthenticated would be the worst kind of
        // compatibility — the code would read « TLS » and mean « encrypted, unauthenticated ».
        assertThat(new RedisConfig("h", 6380, "p", 0).verifyPeer()).isTrue();
        assertThat(new RedisConfig("h", 6380, "p", 0, true).verifyPeer()).isTrue();
        assertThat(RedisConfig.localhost().verifyPeer()).isTrue();
        assertThat(RedisConfig.of("h", 6379).verifyPeer()).isTrue();
        assertThat(RedisConfig.of("h", 6379, "p").verifyPeer()).isTrue();
        assertThat(RedisConfig.of("h", 6379, "p", true).verifyPeer()).isTrue();
        // Relaxed only when asked for, in so many words.
        assertThat(RedisConfig.of("h", 6379, "p", true, false).verifyPeer()).isFalse();
    }

    @Test
    @DisplayName("the URI stops verifying only when ssl AND !verifyPeer")
    void theUriRelaxesOnlyWhenAsked() {
        // `isVerifyPeer` is what Lettuce reads, so it is what this asserts — not the config's own
        // field, which would only prove that a record keeps what it was given.
        assertThat(RedisTransport.uriOf(new RedisConfig("h", 6380, "p", 0, true, true))
                .isVerifyPeer()).isTrue();
        assertThat(RedisTransport.uriOf(new RedisConfig("h", 6380, "p", 0, true, false))
                .isVerifyPeer()).isFalse();
        // And `verifyPeer: false` WITHOUT TLS changes nothing: there is no peer to verify on a
        // cleartext connection, and letting it through would leave a false trace in the URI.
        assertThat(RedisTransport.uriOf(new RedisConfig("h", 6379, "p", 0, false, false))
                .isVerifyPeer()).isTrue();
    }

    @Test
    @DisplayName("toUri follows the flag: rediss:// when ssl")
    void toUriFollowsTheFlag() {
        // This helper has no caller in the repository, which is exactly why it was worth fixing: a
        // dead builder that hardcodes `redis://` is what someone picks up one day believing it works.
        assertThat(new RedisConfig("h", 6380, "p", 1, true).toUri()).startsWith("rediss://");
        assertThat(new RedisConfig("h", 6379, "p", 1, false).toUri()).startsWith("redis://");
    }
}
