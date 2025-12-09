# Conduit

A lightweight, high-performance Java messaging library for distributed microservices communication. Conduit provides a unified abstraction over different message transport mechanisms (Redis, RabbitMQ) with support for broadcast/unicast messaging, acknowledgments, and flexible serialization.

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## Features

- **Multiple Transport Backends**: Redis (PubSub) and RabbitMQ (AMQP) implementations
- **Flexible Messaging Patterns**:
  - Broadcast (pub/sub to all instances)
  - Unicast (direct messaging to specific instance)
  - Acknowledgment support for reliable delivery
- **Type-Safe Packets**: Record-based packet definitions with compile-time safety
- **Custom Channels**: Route different packet types to different channels
- **Pluggable Serialization**: JSON serialization with support for custom serializers
- **Optional Compression**: Built-in Gzip compression support
- **Zero Configuration**: Sensible defaults with builder pattern for customization
- **Test-Friendly**: Comprehensive test utilities and patterns

## Quick Start

### Installation

Add the dependency to your `build.gradle`:

```gradle
dependencies {
    // Choose your transport (conduit-core is included transitively)
    implementation 'fr.traqueur.conduit:conduit-redis:1.0.0'
    // OR
    implementation 'fr.traqueur.conduit:conduit-rabbitmq:1.0.0'
}
```

**Note:** The `conduit-core` module is automatically included as a transitive dependency when you add a transport module.

### Basic Usage

#### 1. Define Your Packets

Packets are simple Java records implementing the `Packet` interface:

```java
import fr.traqueur.conduit.packet.Packet;

public record ChatMessagePacket(UUID senderId, String message) implements Packet {}
```

#### 2. Initialize Conduit

```java
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

// Configure Redis transport
RedisConfig config = RedisConfig.localhost();
RedisTransport transport = new RedisTransport(config);

// Build Conduit instance
Conduit conduit = Conduit.builder()
    .transport(transport)
    .instanceId("server-1")
    .build();
```

#### 3. Register Packets and Handlers

```java
import fr.traqueur.conduit.handler.HandlerResult;

// Register packet type
conduit.registerPacket(ChatMessagePacket.class);

// Register handler for incoming messages
conduit.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
    System.out.println("Received: " + packet.message());
    return HandlerResult.SUCCESS;
});

// Start listening
conduit.start();
```

#### 4. Send Messages

```java
// Broadcast to all instances
ChatMessagePacket packet = new ChatMessagePacket(UUID.randomUUID(), "Hello World!");
packet.send();
```

## Usage Examples

### Broadcast Messaging

Send a message to all connected instances:

```java
public record EventPacket(String eventType, String data) implements Packet {}

// Sender
conduit.registerPacket(EventPacket.class);
new EventPacket("USER_LOGIN", "user123").send();

// Receiver
conduit.registerPacket(EventPacket.class);
conduit.registerHandler(EventPacket.class, (packet, ackCallback) -> {
    System.out.println("Event: " + packet.eventType());
    return HandlerResult.SUCCESS;
});
```

### Unicast Messaging

Send a message to a specific instance:

```java
import fr.traqueur.conduit.packet.TargetablePacket;

public record DirectMessagePacket(String content) implements TargetablePacket {}

// Register and send to specific instance
conduit.registerPacket(DirectMessagePacket.class);
new DirectMessagePacket("Private message").sendTo("server-2");
```

### Acknowledgment Support

Request confirmation when a message is processed:

```java
import fr.traqueur.conduit.packet.AcknowledgeablePacket;
import fr.traqueur.conduit.core.AckResponse;

public record ConfigReloadPacket(String configFile) implements AcknowledgeablePacket {}

// Sender - wait for acknowledgment
conduit.registerPacket(ConfigReloadPacket.class);
new ConfigReloadPacket("server.properties")
    .sendWithAck(5000) // 5 second timeout
    .thenAccept(ack -> {
        if (ack.success()) {
            System.out.println("Config reloaded: " + ack.message());
        } else {
            System.err.println("Failed: " + ack.message());
        }
    });

// Receiver - send acknowledgment
conduit.registerHandler(ConfigReloadPacket.class, (packet, ackCallback) -> {
    try {
        // Reload configuration...
        ackCallback.accept(AckResponse.success(packet.toString(), "Config loaded"));
        return HandlerResult.SUCCESS;
    } catch (Exception e) {
        ackCallback.accept(AckResponse.failure(packet.toString(), e.getMessage()));
        return HandlerResult.ERROR;
    }
});
```

### Custom Channels

Route different packet types to different channels:

```java
import fr.traqueur.conduit.core.PacketMeta;

@PacketMeta(channel = "player-events")
public record PlayerJoinPacket(UUID playerId, String name) implements Packet {}

@PacketMeta(channel = "game-events")
public record GameStartPacket(String gameId) implements Packet {}

// Each packet type automatically uses its designated channel
```

### Compression

Enable Gzip compression for large payloads:

```java
import fr.traqueur.conduit.compression.GzipCompressor;

Conduit conduit = Conduit.builder()
    .transport(transport)
    .compressor(new GzipCompressor())
    .build();
```

## Transport Configurations

### Redis

```java
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

// Localhost (default)
RedisConfig config = RedisConfig.localhost();

// Custom host/port
RedisConfig config = RedisConfig.of("redis.example.com", 6379);

// With authentication
RedisConfig config = RedisConfig.of("redis.example.com", 6379, "password");

// Full configuration
RedisConfig config = new RedisConfig("host", 6379, "password", 0);

RedisTransport transport = new RedisTransport(config);
```

### RabbitMQ

```java
import fr.traqueur.conduit.rabbitmq.RabbitMQConfig;
import fr.traqueur.conduit.rabbitmq.RabbitMQTransport;

// Localhost (default)
RabbitMQConfig config = RabbitMQConfig.localhost();

// Custom host/port
RabbitMQConfig config = RabbitMQConfig.of("rabbitmq.example.com", 5672);

// With authentication
RabbitMQConfig config = RabbitMQConfig.of("rabbitmq.example.com", 5672, "user", "pass");

// Full configuration
RabbitMQConfig config = new RabbitMQConfig("host", 5672, "user", "pass", "/");

RabbitMQTransport transport = new RabbitMQTransport(config);
```

## Advanced Usage

### Combined Unicast + Acknowledgment

```java
import fr.traqueur.conduit.packet.TargetableAcknowledgeablePacket;

public record DataSyncPacket(String data) implements TargetableAcknowledgeablePacket {}

// Send to specific instance and wait for confirmation
conduit.registerPacket(DataSyncPacket.class);
new DataSyncPacket("important-data")
    .sendWithAck("server-2", 10000) // Target instance, 10s timeout
    .thenAccept(ack -> {
        System.out.println("Sync confirmed: " + ack.success());
    });
```

### Multiple Conduit Instances

```java
// Server 1
Conduit server1 = Conduit.builder()
    .transport(new RedisTransport(config))
    .instanceId("server-1")
    .build();

// Server 2
Conduit server2 = Conduit.builder()
    .transport(new RedisTransport(config))
    .instanceId("server-2")
    .build();

// Both can communicate bidirectionally
```

### Custom Serialization

```java
import fr.traqueur.conduit.serialization.Serializer;

public class CustomSerializer implements Serializer {
    @Override
    public byte[] serialize(Packet packet) throws Exception {
        // Custom serialization logic
    }

    @Override
    public <T extends Packet> T deserialize(byte[] data, Class<T> packetClass) throws Exception {
        // Custom deserialization logic
    }

    @Override
    public String getType() {
        return "custom";
    }
}

Conduit conduit = Conduit.builder()
    .transport(transport)
    .serializer(new CustomSerializer())
    .build();
```

### Error Handling

```java
conduit.registerHandler(MyPacket.class, (packet, ackCallback) -> {
    try {
        // Process packet
        if (ackCallback != null) {
            ackCallback.accept(AckResponse.success("id", "Processed"));
        }
        return HandlerResult.SUCCESS;
    } catch (Exception e) {
        if (ackCallback != null) {
            ackCallback.accept(AckResponse.failure("id", e.getMessage()));
        }
        return HandlerResult.ERROR;
    }
});
```

## Architecture

### Packet Lifecycle

**Sending:**
1. User calls `packet.send()` or similar method
2. `Conduit` wraps packet in `PacketEnvelope` with metadata
3. Packet serialized (JSON by default)
4. Data compressed (if enabled)
5. Envelope sent via `Transport` to appropriate channel

**Receiving:**
1. `Transport` receives raw bytes from channel
2. `Conduit` deserializes envelope
3. Filters self-sent broadcast messages
4. Deserializes packet payload
5. Dispatches to registered `PacketHandler`
6. Sends ACK response if required

### Transport Implementations

- **Redis**: Uses Lettuce async client with PubSub. Unicast via `channel:instanceId` pattern.
- **RabbitMQ**: Uses AMQP client with fanout exchanges (broadcast) and direct exchanges (unicast).

## Best Practices

1. **Instance IDs**: Use meaningful instance identifiers (e.g., "lobby-server-1", "game-server-2")
2. **Channel Organization**: Group related packets into custom channels for better organization
3. **Packet Design**: Keep packets immutable and use records for simplicity
4. **Handler Registration**: Register all packets and handlers before calling `start()`
5. **Shutdown**: Always call `conduit.shutdown()` to clean up resources
6. **ACK Timeouts**: Set reasonable timeouts based on expected processing time
7. **Error Handling**: Always handle errors in packet handlers and send appropriate ACK responses

## Testing

Conduit includes comprehensive test utilities:

```java
import fr.traqueur.conduit.core.BaseConduitIntegrationTest;

class MyConduitTest extends BaseConduitIntegrationTest {
    @Override
    protected void setupConduitInstances() throws Exception {
        // Setup two conduit instances for testing
        conduit1 = Conduit.builder()
            .transport(new RedisTransport(config))
            .instanceId("test-1")
            .build();

        conduit2 = Conduit.builder()
            .transport(new RedisTransport(config))
            .instanceId("test-2")
            .build();
    }

    // Tests are inherited from BaseConduitIntegrationTest
}
```

## Performance Considerations

- **Compression**: Use Gzip compression for payloads > 1KB
- **Serialization**: JSON is convenient but consider binary formats for high-throughput scenarios
- **Channel Count**: Limit custom channels to avoid resource overhead
- **ACK Usage**: Only use acknowledgments when delivery confirmation is required
- **Connection Pooling**: Both Redis and RabbitMQ transports handle connection pooling internally

## Requirements

- Java 21 or higher
- Redis 6+ (for Redis transport)
- RabbitMQ 3.8+ (for RabbitMQ transport)

## Building from Source

```bash
./gradlew build
```

Run tests (requires Docker for integration tests):
```bash
./gradlew test
```

## License

[MIT License](LICENSE)

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues and questions, please use the [GitHub Issues](https://github.com/your-repo/conduit/issues) page.
