# Conduit Quick Start Guide

Get up and running with Conduit in 5 minutes!

## Step 1: Add Dependencies

**Recommended — with BOM** (manages all versions centrally):

```kotlin
// build.gradle.kts
dependencies {
    implementation(platform("fr.traqueur.conduit:conduit-bom:1.1.0"))
    implementation("fr.traqueur.conduit:conduit-redis")      // Redis transport
    // OR
    // implementation("fr.traqueur.conduit:conduit-rabbitmq") // RabbitMQ transport
}
```

**Without BOM:**

```gradle
dependencies {
    // Choose your transport (conduit-core is included transitively)
    implementation 'fr.traqueur.conduit:conduit-redis:1.1.0'
    // OR
    // implementation 'fr.traqueur.conduit:conduit-rabbitmq:1.1.0'
}
```

## Step 2: Start Redis

Using Docker:

```bash
docker run -d -p 6379:6379 redis:7-alpine
```

## Step 3: Create a Packet

```java
import fr.traqueur.conduit.packet.Packet;

public record MessagePacket(String from, String text) implements Packet {}
```

## Step 4: Initialize Conduit

```java
import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

// Create Conduit instance
Conduit conduit = Conduit.builder()
    .transport(new RedisTransport(RedisConfig.localhost()))
    .instanceId("my-app")
    .build();

// Register packet type
conduit.registerPacket(MessagePacket.class);
```

## Step 5: Receive Messages

```java
conduit.registerHandler(MessagePacket.class, (packet, ackCallback) -> {
    System.out.println(packet.from() + " says: " + packet.text());
    return null;
});

// Start listening
conduit.start();
```

## Step 6: Send Messages

```java
MessagePacket msg = new MessagePacket("Alice", "Hello World!");
msg.send();
```

## Complete Example

```java
package com.example;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

public class QuickStartDemo {

    public record MessagePacket(String from, String text) implements Packet {}

    public static void main(String[] args) throws Exception {
        // Setup
        Conduit conduit = Conduit.builder()
            .transport(new RedisTransport(RedisConfig.localhost()))
            .instanceId("demo")
            .build();

        conduit.registerPacket(MessagePacket.class);

        // Receive
        conduit.registerHandler(MessagePacket.class, (packet, ackCallback) -> {
            System.out.println(packet.from() + " says: " + packet.text());
            return null;
        });

        conduit.start();

        // Wait for connection
        Thread.sleep(1000);

        // Send
        new MessagePacket("Alice", "Hello World!").send();
        new MessagePacket("Bob", "Hi there!").send();

        // Keep running
        Thread.sleep(5000);

        // Cleanup
        conduit.shutdown();
    }
}
```

Run it and you'll see:

```
Alice says: Hello World!
Bob says: Hi there!
```

## Next Steps

### Unicast Messaging

Send to a specific instance:

```java
public record DirectMsg(String content) implements TargetablePacket {}

new DirectMsg("Private message").sendTo("instance-2");
```

### Request-Response Pattern

Get acknowledgment:

```java
public record TaskPacket(String task) implements AcknowledgeablePacket {}

new TaskPacket("Process data")
    .sendWithAck(5000)
    .thenAccept(ack -> {
        if (ack.success()) {
            System.out.println("Task completed!");
        }
    });
```

### Custom Channels

Organize by topic:

```java
@PacketMeta(channel = "notifications")
public record NotifyPacket(String message) implements Packet {}
```

### Use RabbitMQ

Switch transport:

```java
import fr.traqueur.conduit.rabbitmq.*;

Conduit conduit = Conduit.builder()
    .transport(new RabbitMQTransport(RabbitMQConfig.localhost()))
    .build();
```

## More Examples

Check the [examples/](examples/) directory for:

- **Basic**: Broadcast, unicast, acknowledgments, custom channels
- **Advanced**: Load balancing, pub-sub patterns, compression, multi-transport

## Documentation

- [Full README](README.md) - Complete feature documentation
- [Examples README](examples/README.md) - Detailed example guides
- [Javadoc](build/docs/javadoc/) - API documentation

## Troubleshooting

### Can't connect to Redis?

```bash
# Verify Redis is running
docker ps

# Test connection
telnet localhost 6379
```

### Messages not received?

1. Call `conduit.start()` on all instances
2. Wait 1-2 seconds after `start()` before sending
3. Register packets on both sender and receiver
4. Use unique instance IDs

### Import errors?

Verify dependencies in your build file:

```kotlin
// With BOM (recommended)
implementation(platform("fr.traqueur.conduit:conduit-bom:1.1.0"))
implementation("fr.traqueur.conduit:conduit-redis")
```

```gradle
// Without BOM
implementation 'fr.traqueur.conduit:conduit-redis:1.1.0'
```

## Support

- [GitHub Issues](https://github.com/your-repo/conduit/issues)
- [Main Documentation](README.md)

Happy messaging! 🚀
