package fr.traqueur.conduit.core;

import fr.traqueur.conduit.memory.InMemoryBus;
import fr.traqueur.conduit.transport.Transport;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;

/**
 * The shared suite on the asynchronous in-memory bus: delivery on another thread, in order, as a
 * real transport does it.
 */
class AsyncInMemoryConduitIntegrationTest extends BaseConduitIntegrationTest {

    private final InMemoryBus bus = InMemoryBus.async();

    @Override
    protected Transport newTransport(String channelPrefix) {
        return bus.transport(channelPrefix);
    }

    @Override
    protected Duration settle() {
        return Duration.ofMillis(100);
    }

    @AfterEach
    void closeBus() {
        bus.close();
    }
}
