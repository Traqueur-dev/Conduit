package fr.traqueur.conduit.redis;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link RedisTransport} will dial, asserted on {@link RedisTransport#uriOf} itself: a copy of
 * it in the test would pass even when the transport forgets a field.
 *
 * <p>TLS is a property of the PORT: a cleartext client on a TLS port does not degrade, it never
 * connects — so the flag travelling from the config to the URI is what matters here.
 */
class RedisConfigTest {

    @Test
    @DisplayName("Every component reaches the URI")
    void componentsReachTheUri() {
        RedisURI uri = RedisTransport.uriOf(RedisConfig.builder("host", 6380)
                .credentials("lobby", "secret")
                .database(3)
                .ssl()
                .timeout(Duration.ofSeconds(2))
                .build());

        assertThat(uri.getHost()).isEqualTo("host");
        assertThat(uri.getPort()).isEqualTo(6380);
        assertThat(uri.getDatabase()).isEqualTo(3);
        RedisCredentials credentials = credentials(uri);
        assertThat(credentials.getUsername()).isEqualTo("lobby");
        assertThat(credentials.getPassword()).isEqualTo("secret".toCharArray());
        assertThat(uri.isSsl()).isTrue();
        assertThat(uri.isVerifyPeer()).isTrue();
        assertThat(uri.getTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Defaults: cleartext, default user, Lettuce's timeout")
    void defaults() {
        RedisURI uri = RedisTransport.uriOf(RedisConfig.of("h", 6379));
        assertThat(uri.isSsl()).isFalse();
        assertThat(credentials(uri).hasUsername()).isFalse();
        assertThat(credentials(uri).hasPassword()).isFalse();
        assertThat(uri.getTimeout()).isEqualTo(RedisURI.DEFAULT_TIMEOUT_DURATION);
        RedisCredentials passwordOnly = credentials(RedisTransport.uriOf(RedisConfig.builder("h", 6379).password("p").build()));
        assertThat(passwordOnly.hasUsername()).isFalse();
        assertThat(passwordOnly.getPassword()).isEqualTo("p".toCharArray());
    }

    private static RedisCredentials credentials(RedisURI uri) {
        return uri.getCredentialsProvider().resolveCredentials().block();
    }

    @Test
    @DisplayName("The peer is verified unless relaxed in so many words")
    void verifyPeerOnlyRelaxedWhenAsked() {
        assertThat(RedisConfig.builder("h", 6380).ssl().build().verifyPeer()).isTrue();
        RedisURI relaxed = RedisTransport.uriOf(RedisConfig.builder("h", 6380).sslWithoutPeerVerification().build());
        assertThat(relaxed.isSsl()).isTrue();
        assertThat(relaxed.isVerifyPeer()).isFalse();
    }

    @Test
    @DisplayName("The password never appears in toString()")
    void passwordStaysOutOfToString() {
        String text = RedisConfig.builder("h", 6379).credentials("lobby", "hunter2").build().toString();
        assertThat(text).doesNotContain("hunter2").contains("lobby").contains("***");
    }
}
