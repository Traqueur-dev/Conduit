package fr.traqueur.conduit.redis;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Reproduces the classloader conflict that occurs when two Minecraft plugins
 * each load Conduit (and Netty transitively) via their own isolated PluginClassLoader.
 *
 * In PaperSpigot, each plugin's PluginClassLoader has the server classloader as parent
 * but loads its own bundled JARs first. Two plugins bundling Lettuce/Netty will each
 * initialize their own copy of Netty's static resources (DNS resolver, EventLoopGroup),
 * which causes conflicts on the second initialization.
 *
 * This test simulates that scenario using two URLClassLoaders whose parent is the
 * platform classloader (i.e., they do NOT share the test classloader), forcing each
 * to load its own copy of Lettuce and Netty classes.
 */
@Testcontainers
class DualClassLoaderRedisTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * Builds the classpath URLs from the current JVM classpath.
     * In Gradle test execution, java.class.path contains all test + runtime JARs.
     */
    private URL[] classpathUrls() throws Exception {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(entry -> {
                    try {
                        return new File(entry).toURI().toURL();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toArray(URL[]::new);
    }

    /**
     * Creates a RedisTransport via reflection inside the given classloader, connects it,
     * and returns the transport object (typed as Object since its class is foreign to this CL).
     */
    private Object createAndConnect(URLClassLoader cl, String host, int port) throws Exception {
        Class<?> configClass = cl.loadClass("fr.traqueur.conduit.redis.RedisConfig");
        Constructor<?> configCtor = configClass.getDeclaredConstructors()[0];
        Object config = configCtor.newInstance(host, port, null, 0);

        Class<?> transportClass = cl.loadClass("fr.traqueur.conduit.redis.RedisTransport");
        Constructor<?> transportCtor = transportClass.getDeclaredConstructor(configClass);
        Object transport = transportCtor.newInstance(config);

        Method connect = transportClass.getMethod("connect");
        connect.invoke(transport);

        return transport;
    }

    private boolean isConnected(URLClassLoader cl, Object transport) throws Exception {
        Class<?> transportClass = cl.loadClass("fr.traqueur.conduit.redis.RedisTransport");
        Method isConnected = transportClass.getMethod("isConnected");
        return (boolean) isConnected.invoke(transport);
    }

    private void close(URLClassLoader cl, Object transport) throws Exception {
        Class<?> transportClass = cl.loadClass("fr.traqueur.conduit.redis.RedisTransport");
        transportClass.getMethod("close").invoke(transport);
    }

    @Test
    void twoPluginsWithIsolatedClassLoaders_shouldBothConnect() throws Exception {
        String host = redis.getHost();
        int port = redis.getFirstMappedPort();

        URL[] urls = classpathUrls();

        // Each URLClassLoader has the system CL as parent, mirroring PaperSpigot's
        // PluginClassLoader which shares server classes (including Paper's bundled Netty)
        // but loads each plugin's bundled Lettuce separately.
        URLClassLoader cl1 = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
        URLClassLoader cl2 = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());

        Object transport1 = null;
        Object transport2 = null;

        try {
            // Plugin A connects first — initializes Netty statics in cl1
            transport1 = createAndConnect(cl1, host, port);
            assertThat(isConnected(cl1, transport1))
                    .as("Plugin A (classloader 1) should be connected")
                    .isTrue();

            // Plugin B connects second — tries to re-initialize Netty statics in cl2
            // Without the fix this throws a Netty DNS resolver error
            transport2 = createAndConnect(cl2, host, port);
            assertThat(isConnected(cl2, transport2))
                    .as("Plugin B (classloader 2) should be connected")
                    .isTrue();
        } finally {
            if (transport1 != null) close(cl1, transport1);
            if (transport2 != null) close(cl2, transport2);
            cl1.close();
            cl2.close();
        }
    }
}
