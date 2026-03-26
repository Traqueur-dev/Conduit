# Conduit Examples

This directory contains comprehensive examples demonstrating various features and usage patterns of the Conduit messaging library.

## Prerequisites

Before running these examples, ensure you have:

- Java 21 or higher installed
- Redis server running (for Redis examples) on `localhost:6379`
- RabbitMQ server running (for RabbitMQ examples) on `localhost:5672`

You can quickly start Redis and RabbitMQ using Docker:

```bash
# Start Redis
docker run -d -p 6379:6379 redis:7-alpine

# Start RabbitMQ
docker run -d -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```

## Basic Examples

Located in `basic/` directory. These examples cover fundamental Conduit features:

### 1. SimpleBroadcastExample.java

**What it demonstrates:**
- Basic broadcast messaging to all connected instances
- Simple packet definition
- Handler registration
- Message sending and receiving

**Run it:**
```bash
java examples.basic.SimpleBroadcastExample
```

**Expected output:**
```
Message sent, waiting for delivery...
Received message from <uuid>: Hello from sender!
```

---

### 2. UnicastExample.java

**What it demonstrates:**
- Unicast messaging (sending to specific instance)
- TargetablePacket interface
- Instance targeting with `sendTo()`

**Run it:**
```bash
java examples.basic.UnicastExample
```

**Expected output:**
```
Sending message to server-2 only...
Server-2 received: This message is only for server-2
```
(Note: Only server-2 receives the message, not server-1 or server-3)

---

### 3. AcknowledgmentExample.java

**What it demonstrates:**
- Request-response pattern with acknowledgments
- AcknowledgeablePacket interface
- Success and failure acknowledgment handling
- Timeout configuration

**Run it:**
```bash
java examples.basic.AcknowledgmentExample
```

**Expected output:**
```
Sending task and waiting for acknowledgment...
Worker processing task: task-123
Processing data-processing task...
Task completed successfully: Task task-123 completed successfully
```

---

### 4. CustomChannelExample.java

**What it demonstrates:**
- Using @PacketMeta annotation for custom channels
- Channel-based message routing
- Organizing different event types

**Run it:**
```bash
java examples.basic.CustomChannelExample
```

**Expected output:**
```
Sending packets on different channels...

[SYSTEM] Server initialized
[PLAYER] Alice joined
[PLAYER] Bob joined
[GAME] Game game-123 started with 2 players
[GAME] Game game-123 ended. Winner: Alice
[PLAYER] Player <uuid> left
```

---

## Advanced Examples

Located in `advanced/` directory. These examples demonstrate more complex patterns:

### 1. MultiTransportExample.java

**What it demonstrates:**
- Using Redis and RabbitMQ transports simultaneously
- Transport isolation (instances on different transports don't communicate)
- Transport comparison

**Run it:**
```bash
java examples.advanced.MultiTransportExample
```

**Expected output:**
```
Starting multi-transport example...

Sending from Redis instance...
[Redis Instance] Received: REDIS_EVENT - Message from Redis

Sending from RabbitMQ instance...
[RabbitMQ Instance] Received: RABBITMQ_EVENT - Message from RabbitMQ

Note: Instances on different transports don't communicate with each other.
Each transport creates its own isolated messaging network.
```

---

### 2. CompressionExample.java

**What it demonstrates:**
- Performance comparison: compressed vs uncompressed
- GzipCompressor usage
- When to use compression

**Run it:**
```bash
java examples.advanced.CompressionExample
```

**Expected output:**
```
Compression Example

=== Without Compression ===
Received payload of length: 10000
Transfer completed in XXXms
Payload size: 10000 bytes

=== With Gzip Compression ===
Received payload of length: 10000
Transfer completed in XXXms
Payload size: 10000 bytes

Conclusion:
- Compression reduces network bandwidth for large payloads
- Use compression for payloads > 1KB
- Trade-off: CPU time for network bandwidth
```

---

### 3. LoadBalancingExample.java

**What it demonstrates:**
- Distributing work across multiple workers
- Round-robin load balancing
- Unicast with acknowledgment for task completion
- Coordinated distributed processing

**Run it:**
```bash
java examples.advanced.LoadBalancingExample
```

**Expected output:**
```
Load Balancing Example

Distributing 10 tasks across 3 workers...

Assigning task 1 to worker-1
Assigning task 2 to worker-2
Assigning task 3 to worker-3
...

Waiting for all tasks to complete...

worker-1 processing task 1
worker-2 processing task 2
worker-3 processing task 3
...

✓ Task 1 completed by worker-1
✓ Task 2 completed by worker-2
✓ Task 3 completed by worker-3
...

Completed 10/10 tasks successfully
```

---

### 4. PubSubPatternExample.java

**What it demonstrates:**
- Event-driven architecture
- Multiple specialized subscribers
- Publisher-subscriber pattern
- Real-world e-commerce event flow

**Run it:**
```bash
java examples.advanced.PubSubPatternExample
```

**Expected output:**
```
Publisher-Subscriber Pattern Example

Publishing business events...

--- Order Flow ---
[Order Processor] New order: ORD-001 ($149.99)
[Analytics] Logged order event
[Inventory Manager] Stock updated: Product PROD-123 = 45 units
[Analytics] Logged inventory event
[Notification Service] Sending to user user-456: Your order ORD-001 has been confirmed
[Analytics] Logged notification event
[Order Processor] Order shipped: ORD-001 (Tracking: TRACK-XYZ789)
[Analytics] Logged shipping event
...
```

---

## Running Examples

### Option 1: Direct Java Execution

If you have the Conduit JARs in your classpath:

```bash
java -cp "conduit-core-1.1.0.jar:conduit-redis-1.1.0.jar:..." examples.basic.SimpleBroadcastExample
```

### Option 2: Using Gradle

Add examples to your project and run:

```bash
./gradlew runExample -PmainClass=examples.basic.SimpleBroadcastExample
```

### Option 3: IDE

Import the examples into your IDE (IntelliJ IDEA, Eclipse, etc.) and run directly.

## Common Patterns Demonstrated

### 1. Basic Messaging
- **Broadcast**: Send to all instances (`SimpleBroadcastExample`)
- **Unicast**: Send to specific instance (`UnicastExample`)
- **Request-Response**: With acknowledgments (`AcknowledgmentExample`)

### 2. Organization
- **Custom Channels**: Route by topic (`CustomChannelExample`)
- **Event Types**: Different packet types for different purposes (`PubSubPatternExample`)

### 3. Distributed Systems
- **Load Balancing**: Distribute work (`LoadBalancingExample`)
- **Event-Driven**: Publish-subscribe architecture (`PubSubPatternExample`)

### 4. Optimization
- **Compression**: Reduce bandwidth (`CompressionExample`)
- **Transport Selection**: Choose appropriate transport (`MultiTransportExample`)

## Troubleshooting

### Connection Issues

If you see connection errors:

```
Failed to connect to Redis/RabbitMQ
```

**Solution:**
1. Verify Redis/RabbitMQ is running: `docker ps`
2. Check connectivity: `telnet localhost 6379` (Redis) or `telnet localhost 5672` (RabbitMQ)
3. Review firewall settings

### No Messages Received

If handlers aren't called:

**Solution:**
1. Ensure `start()` is called on all Conduit instances
2. Wait 1-2 seconds after `start()` before sending
3. Verify packet types are registered on both sender and receiver
4. Check instance IDs are unique

### Acknowledgment Timeout

If ACK futures timeout:

**Solution:**
1. Increase timeout value: `.sendWithAck(30000)` (30 seconds)
2. Verify receiver has handler registered
3. Check receiver is calling `ackCallback.accept()`
4. Review network latency

## Next Steps

After exploring these examples:

1. **Read the main README.md** for detailed API documentation
2. **Review the Javadoc** for in-depth method documentation
3. **Check the test suite** in `src/test/` for more examples
4. **Build your own application** using Conduit

## Questions or Issues?

If you encounter problems or have questions:

1. Check the main [README.md](../README.md)
2. Review the [Javadoc documentation](../build/docs/javadoc/)
3. Look at the [test suite](../conduit-core/src/test/)
4. Open an issue on GitHub

Happy messaging! 🚀
