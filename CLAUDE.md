# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Conduit is a Java messaging system for distributed communication across microservices. It provides a unified abstraction layer over different transport mechanisms (Redis, RabbitMQ) with support for broadcast/unicast messaging, acknowledgments, and custom serialization/compression.

**Key Concepts:**
- **Packets**: Records implementing the `Packet` interface that define message structure
- **Conduit**: Multi-instance registry managing packet routing, serialization, and transport (supports multiple named instances)
- **Transport**: Abstraction for different messaging backends (Redis PubSub, RabbitMQ exchanges)
- **Handlers**: Callbacks registered to process incoming packets (sync `PacketHandler` or async `AsyncPacketHandler`)
- **Channels**: Logical message routing paths, either default or custom via `@PacketMeta` annotation

## Project Structure

This is a Gradle multi-module project with three modules:

- **conduit-core**: Core abstractions including `Conduit`, `Transport`, `Packet`, serialization/compression
- **conduit-redis**: Redis transport implementation using Lettuce async client
- **conduit-rabbitmq**: RabbitMQ transport implementation using AMQP client

## Build Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :conduit-core:build
./gradlew :conduit-redis:build
./gradlew :conduit-rabbitmq:build

# Clean build
./gradlew clean build

# Run tests
./gradlew test

# Run tests for specific module
./gradlew :conduit-core:test

# Run single test class
./gradlew :conduit-core:test --tests "fr.traqueur.conduit.core.BaseConduitIntegrationTest"

# Generate Javadoc
./gradlew javadoc

# Publish to Maven repository
./gradlew publishAll  # Publishes all subprojects
```

## Architecture

### Initialization Flow

1. Create `Conduit` instance via builder with chosen transport (optional `.name()` for registry lookup)
2. Register packet types via `registerPacket()`
3. Register handlers via `registerHandler()` or `registerAsyncHandler()`
4. Call `start()` to connect transport and subscribe to channels
5. Send/receive packets via `send()`, `sendTo()`, `sendWithAck()`
6. On shutdown, call `shutdown()` (removes instance from registry)

### Packet Lifecycle

**Sending:**
1. User calls `packet.send()` or similar method
2. `Conduit` wraps packet in `PacketEnvelope` with metadata
3. Packet serialized via `Serializer` (default: JSON)
4. Data compressed via `Compressor` (default: no compression)
5. Envelope sent via `Transport` to appropriate channel

**Receiving:**
1. `Transport` receives raw bytes from channel
2. `Conduit.handleIncomingPacket()` deserializes envelope
3. Checks if packet is from self (ignores broadcast loops)
4. Guards against empty payload (WARN log)
5. Deserializes packet payload to registered packet class
6. Dispatches to registered `PacketHandler` or `AsyncPacketHandler` (via `dispatchAsync`)
7. If ACK required, sends response back to sender; if no handler, sends automatic NACK

### Transport Implementations

**Redis (`RedisTransport`):**
- Uses Lettuce async client with PubSub
- Broadcast: publishes to channel
- Unicast: publishes to `channel:instanceId`
- ACK: temporary channels `channel:ack:ackId`
- Metadata injection: `redis.ackChannel` for ACK routing

**RabbitMQ (`RabbitMQTransport`):**
- Uses RabbitMQ AMQP client
- Broadcast: fanout exchanges `conduit.broadcast.{channel}`
- Unicast: direct exchanges `conduit.direct.{channel}` with routing key = instanceId
- ACK: RabbitMQ request/reply pattern with correlationId and temporary reply queues
- Metadata injection: `rabbitmq.replyTo` and `rabbitmq.correlationId` for ACK routing

### Key Design Patterns

- **Registry**: `Conduit` instances are stored in a static `ConcurrentHashMap`; first built becomes `defaultInstance`. Named instances retrieved via `getInstance(String)`.
- **Builder**: `Conduit.builder()` for flexible configuration, with optional `.name(String)` for registry key
- **Template Method**: `Transport` interface with default `sendAckResponse()` method
- **Registry**: `PacketRegistry` and `HandlerRegistry` for type-safe packet/handler lookup
- **Envelope Pattern**: `PacketEnvelope` wraps packets with metadata and routing info

## Module Dependencies

```
conduit-core (no dependencies)
    |
    +-- conduit-redis (depends on core + Lettuce)
    |
    +-- conduit-rabbitmq (depends on core + RabbitMQ AMQP client)
```

**Test Dependencies:**
- JUnit Jupiter 6.0.1
- AssertJ 3.27.6
- Awaitility 4.3.0 (for async testing)
- Testcontainers (Redis/RabbitMQ modules for integration tests)

## Testing Strategy

- **Unit tests**: Test individual components in isolation
- **Integration tests**: Use Testcontainers to spin up real Redis/RabbitMQ instances
- Base test class `BaseConduitIntegrationTest` in conduit-core provides shared test utilities
- Test artifacts from conduit-core are exported via `testArtifacts` configuration for reuse in transport modules

## Important Implementation Notes

1. **Instance ID filtering**: Conduit automatically filters out broadcast messages sent by itself using `instanceId` in envelope metadata
2. **ACK routing**: Each transport implements custom ACK routing via metadata (Redis uses temporary channels, RabbitMQ uses reply queues)
3. **Channel subscription**: Channels are subscribed at `start()` time, including default channel + any custom channels from `@PacketMeta`
4. **Async operations**: Redis transport uses fully async operations with `CompletableFuture`, RabbitMQ uses blocking operations internally
5. **Error handling**: `PacketHandler` is void; if no handler exists, automatic NACK is sent. `AsyncPacketHandler` returns `CompletableFuture<Void>`; exceptions cause NACK.
6. **Handler types**: Use `registerHandler()` for synchronous handlers, `registerAsyncHandler()` for async. No default executor provided — supply your own via `CompletableFuture.runAsync(..., executor)`.
7. **Multi-instance**: `Conduit.resetAll()` (package-private) is available in tests via `BaseConduitIntegrationTest` to clear all instances between test runs.
8. **RabbitMQ thread-safety**: Two separate channels (`consumeChannel`/`publishChannel`) with `publishLock` protecting all publish operations. Exchange declarations are cached in `declaredExchanges`.

## Maven Publishing

The project publishes to a custom Maven repository at `https://repo.groupez.dev/`. Credentials are required via environment variables `MAVEN_USERNAME` and `MAVEN_PASSWORD` or Gradle properties.

## CI/CD

GitHub Actions workflow at `.github/workflows/build.yml` triggers on push/PR to main/develop branches and uses a reusable workflow from `GroupeZ-dev/actions` repository.