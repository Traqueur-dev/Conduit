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

#### Recommended: using the BOM

Import `conduit-bom` to manage all module and dependency versions centrally:

**Gradle (Kotlin DSL):**
```kotlin
dependencies {
    implementation(platform("fr.traqueur.conduit:conduit-bom:2.0.0"))
    implementation("fr.traqueur.conduit:conduit-redis")      // Redis transport
    // OR
    implementation("fr.traqueur.conduit:conduit-rabbitmq")  // RabbitMQ transport
}
```

**Maven:**
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>fr.traqueur.conduit</groupId>
      <artifactId>conduit-bom</artifactId>
      <version>2.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>fr.traqueur.conduit</groupId>
    <artifactId>conduit-redis</artifactId>
  </dependency>
</dependencies>
```

#### Without BOM

```gradle
dependencies {
    // Choose your transport (conduit-core is included transitively)
    implementation 'fr.traqueur.conduit:conduit-redis:2.0.0'
    // OR
    implementation 'fr.traqueur.conduit:conduit-rabbitmq:2.0.0'
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
// Register packet type
conduit.registerPacket(ChatMessagePacket.class);

// Register handler for incoming messages
conduit.registerHandler(ChatMessagePacket.class, (packet, ackCallback) -> {
    System.out.println("Received: " + packet.message());
    return null; // null = sync completion
});

// Start listening
conduit.start();
```

#### 4. Send Messages

```java
// Broadcast to all instances; the future says whether the transport published it
conduit.send(new ChatMessagePacket(UUID.randomUUID(), "Hello World!"))
    .exceptionally(error -> { log.warn("not sent", error); return null; });

// Or through the default instance
new ChatMessagePacket(UUID.randomUUID(), "Hello World!").send();
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
    return null;
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
    } catch (Exception e) {
        ackCallback.accept(AckResponse.failure(packet.toString(), e.getMessage()));
    }
    return null;
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

// Full configuration: ACL user, database, TLS, command timeout
RedisConfig config = RedisConfig.builder("redis.example.com", 6380)
    .credentials("lobby", "password")
    .database(0)
    .ssl()
    .timeout(Duration.ofSeconds(2))
    .build();

RedisTransport transport = new RedisTransport(config);

// Or from a Lettuce URI (anything RedisConfig does not cover)
RedisTransport transport = new RedisTransport(RedisURI.create("rediss://lobby:pw@redis:6380/0"));

// Or on the application's own client, which the transport never closes,
// with every channel prefixed so environments sharing one Redis do not see each other
RedisTransport transport = RedisTransport.builder()
    .client(applicationRedisClient)
    .channelPrefix("prod:")
    .build();
```

A refused publication (Redis down, ACL `NOPERM`...) fails the future returned by `send`.

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

// Or from a ConnectionFactory (TLS, timeouts, credentials provider...)
RabbitMQTransport transport = new RabbitMQTransport(connectionFactory);

// Or on the application's own connection, which the transport never closes,
// with every exchange and queue prefixed so environments sharing one broker do not see each other
RabbitMQTransport transport = RabbitMQTransport.builder()
    .connection(applicationConnection)
    .channelPrefix("prod:")
    .build();
```

Publishing uses publisher confirms. A refused publication (nack, `ACCESS_REFUSED`, closed channel)
fails the future returned by `send`. A RabbitMQ user confined to one prefix
(`set_permissions ... "^prod:.*" "^prod:.*" "^prod:.*"`) cannot touch another environment.

## Advanced Usage

### Wire Names

The name a type travels under is what receivers use to find its class. Make it explicit, unique,
and stable across renames:

```java
@PacketMeta(channel = "shop", wire = "shop.rotation.v1")
public record ShopRotationPacket(String shopId, String periodKey) implements Packet {}

conduit.registerPacket(ShopRotationPacket.class);                    // uses `wire`
conduit.registerPacket("shop.rotation.v1", ShopRotationPacket.class); // or explicitly
```

Without `wire`, the class simple name is used. One wire name for two classes is refused.
`Conduit.builder().requireWireNames()` refuses any type without an explicit name.

### Registering and Removing at Runtime

Types and handlers can come and go after `start()`: a module enabled on a running server works.

```java
conduit.registerPacket(ShopRotationPacket.class).join(); // subscribes "shop" if needed
conduit.registerHandler(ShopRotationPacket.class, handler);
// ...
conduit.unregisterPacket(ShopRotationPacket.class);       // removes the type and its handler;
                                                          // unsubscribes "shop" once unused
```

Channels are reference-counted, and the default channel is never unsubscribed. A packet of a
removed type is handled as an unknown type: it is logged once at debug level, and NACKed if it
asked for an ACK.

### Handler Executor

```java
Conduit.builder()
    .transport(transport)
    .handlerExecutor(mainThreadExecutor) // default: the transport thread
    .build();
```

Every handler runs on that executor; decoding stays on the transport thread. A single-threaded
executor keeps the order of reception. A refused task drops the packet: it is logged, counted, and
NACKed if it asked for an ACK.

### Signed Envelopes

```java
Conduit.builder()
    .signer(HmacSigner.sha256("paper-lobby-1", secret))
    .verifier(HmacVerifier.sha256(keysById).and((keyId, env) -> myPolicy(keyId, env)))
    .maxClockSkew(Duration.ofSeconds(30))
    .build();
```

With a verifier, an envelope is refused (no handler, no NACK, counted) if it is unsigned, badly
signed, too old or too far in the future, a replay, built for another channel or target, or
refused by the policy. ACKs are signed too. The exact format and the order of the checks are in
[docs/ENVELOPE.md](docs/ENVELOPE.md).

### Metrics

```java
ConduitMetrics m = conduit.metrics();
m.sent(); m.received(); m.sendFailures(); m.handlerFailures();
m.refused(); m.ackTimeouts(); m.unknownType();
m.byWire(); // Map<String, Counts>, per registered wire name
```

### Instances Outside the Registry

`Conduit.builder()...unregistered().build()` creates an instance that is never the default and
never found by `getInstance(name)`. Use it when several libraries in one JVM each embed Conduit,
and send with `conduit.send(packet)`.

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
    } catch (Exception e) {
        if (ackCallback != null) {
            ackCallback.accept(AckResponse.failure("id", e.getMessage()));
        }
    }
    return null;
});
```

## Architecture

### Packet Lifecycle

**Sending:**
1. User calls `conduit.send(packet)` (or `packet.send()`)
2. Packet serialized (JSON by default), then compressed (if enabled)
3. `Conduit` wraps it in a `PacketEnvelope`: id, timestamp, sender, channel, target, wire name, and a signature if a signer is set
4. Envelope published via `Transport`; the returned future completes once it is published

**Receiving:**
1. `Transport` receives raw bytes from channel
2. `Conduit` decodes the envelope and ignores its own broadcasts
3. Checks routing, then signature, clock skew and replay (if a verifier is set)
4. Looks the wire name up, deserializes the payload
5. Dispatches to the registered `PacketHandler` on the handler executor
6. Sends the ACK response to the sender's reply channel if required

### Transport Implementations

- **Redis**: Uses Lettuce async client with PubSub. Unicast via `channel:instanceId` pattern.
- **RabbitMQ**: Uses AMQP client with fanout exchanges (broadcast) and direct exchanges (unicast).
- **In-memory** (`InMemoryBus`, in `conduit-core`): the same semantics in-process, for tests.

ACKs travel back on one reply channel per instance (`<defaultChannel>:ack`, unicast to the
requester), subscribed once at start.

## Best Practices

1. **Instance IDs**: Use meaningful instance identifiers (e.g., "lobby-server-1", "game-server-2")
2. **Channel Organization**: Group related packets into custom channels for better organization
3. **Packet Design**: Keep packets immutable and use records for simplicity
4. **Wire Names**: Give every packet an explicit `@PacketMeta(wire = ...)` and turn on `requireWireNames()`
5. **Shutdown**: Always call `conduit.shutdown()` to clean up resources
6. **ACK Timeouts**: Set reasonable timeouts based on expected processing time
7. **Error Handling**: Always handle errors in packet handlers and send appropriate ACK responses

## Testing

No Docker needed: an `InMemoryBus` connects instances in-process, with the semantics of Redis.

```java
InMemoryBus bus = new InMemoryBus();                 // synchronous: delivered before send returns
Conduit a = Conduit.builder().transport(bus.transport()).instanceId("a").unregistered().build();
Conduit b = Conduit.builder().transport(bus.transport()).instanceId("b").unregistered().build();
// register, start, then:
a.send(new ChatMessagePacket(id, "hi"));             // b's handler has already run here

bus.disconnect(a.transport());  // a's sends fail, a receives nothing
bus.dropNext(2);                // the next two messages are lost
bus.dropAcks(1);                // the next ACK never arrives: the request times out
InMemoryBus.async();            // delivery on a background thread, in order
```

The shared suite `BaseConduitIntegrationTest` (test artifact of `conduit-core`) runs on every
transport; a transport module only provides `newTransport()`.

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
