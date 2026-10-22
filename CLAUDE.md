# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Conduit is a Java messaging library for communication between services. It provides one
abstraction over several transports (Redis Pub/Sub, RabbitMQ, in-memory), with broadcast and
unicast, acknowledgments, signed envelopes, and custom serialization and compression.

**Key Concepts:**
- **Packets**: Records implementing `Packet` (or `TargetablePacket`, `AcknowledgeablePacket`, `TargetableAcknowledgeablePacket`)
- **Wire name**: The name a type travels under: `@PacketMeta(wire = ...)`, `registerPacket(wire, class)`, or else the simple class name
- **Conduit**: One instance per process (or per library): packet/handler registries, routing, ACKs, verification. Instances are kept in a static registry unless built `.unregistered()`
- **Transport**: Moves opaque bytes on named channels; knows nothing about ACKs or signatures
- **Handlers**: A single `PacketHandler<T>` type. It returns `CompletableFuture<Void>`, or `null` for synchronous completion, and runs on the builder's `handlerExecutor` (default: the transport thread)
- **Channels**: The default channel, plus any `@PacketMeta(channel = ...)`, reference-counted per registered type

## Project Structure

Gradle multi-module project:

- **conduit-core**: `Conduit`, `PacketEnvelope`, `ConduitMetrics`, registries, `Transport`, serialization/compression, `security` (signer/verifier, HMAC), `memory` (`InMemoryBus`, `InMemoryTransport`)
- **conduit-redis**: `RedisTransport` (Lettuce async), `RedisConfig`
- **conduit-rabbitmq**: `RabbitMQTransport` (AMQP client), `RabbitMQConfig`
- **conduit-bom**: version alignment

## Build Commands

```bash
./gradlew build                      # build + test everything (Redis/RabbitMQ tests need Docker)
./gradlew :conduit-core:test         # core + in-memory tests, no Docker
./gradlew :conduit-redis:test
./gradlew :conduit-rabbitmq:test
./gradlew :conduit-core:test --tests "fr.traqueur.conduit.core.InMemoryConduitIntegrationTest"
./gradlew javadoc
./gradlew publishAll
```

## Architecture

### Initialization Flow

1. `Conduit.builder().transport(t)...build()` (options: `name`, `instanceId`, `defaultChannel`, `unregistered`, `requireWireNames`, `handlerExecutor`, `signer`, `verifier`, `maxClockSkew`, `clock`)
2. `registerPacket()` / `registerHandler()`, before or after start
3. `start()`: connects, subscribes the default channel, the reply channel `<defaultChannel>:ack` (unicast) and every registered custom channel, and returns once all subscriptions are active
4. `send()` / `sendTo()` return `CompletableFuture<Void>` (published or failed); `sendWithAck()` returns `CompletableFuture<AckResponse>`
5. `unregisterPacket()` / `unregisterHandler()` at any time
6. `shutdown()`: leaves the registry, fails pending ACKs, closes the transport

### Packet Lifecycle

**Sending:** serialize → compress → `PacketEnvelope.packet(...)` (packetId, timestamp, sender, namespace = transport prefix, channel, target, wire name, reply channel) → `encode(signer)` → `transport.broadcast/sendTo`.

**Receiving** (`Conduit.receive`):
1. Decode (version byte `0x02`; anything else is refused)
2. Ignore own broadcasts
3. Routing checks: namespace + channel equal where it arrived; target null on broadcast, self on unicast; ACK responses only on the reply channel
4. If a verifier is set: signature present, verifier verdict, clock skew, replay guard (`keyId/packetId` kept for `maxClockSkew`)
5. ACK response → completes the pending future (unicast requests: only from the target)
6. Unknown wire name → `unknownType` counter, debug log once, NACK if an ACK was requested
7. Deserialize, then dispatch on `handlerExecutor`. A rejection, a thrown exception or a failed future → `handlerFailures` + NACK

Refusals are counted (`refused`), never answered, and logged at most once per sender per minute.

### Transport Implementations

Every transport applies an optional `channelPrefix()` to physical names. That prefix is signed as the envelope's namespace.

- **Redis**: Broadcast `<prefix><channel>`, unicast `<prefix><channel>:<instanceId>`, with one `Dispatcher` listener routing by channel. Built from `RedisConfig`, a `RedisURI` or a provided `RedisClient`. A provided client and its resources are never closed.
- **RabbitMQ**: Fanout `conduit.broadcast.<channel>` (exclusive queue per subscriber), direct `conduit.direct.<channel>` (routing key = instanceId). Separate publish/consume channels, each with its own lock; `declaredExchanges` cache. Blocking: futures are already complete.
- **In-memory**: `InMemoryBus` with Redis semantics. Synchronous by default, `InMemoryBus.async()` for ordered background delivery. `disconnect`/`reconnect`/`dropNext`/`dropAcks` simulate failures.

ACKs never go through transport-specific code: a responder does `transport.sendTo(replyChannel, requesterId, ackEnvelope)`.

## Important Implementation Notes

1. **Wire format**: See `docs/ENVELOPE.md`. There are no metadata maps; every byte except the signature is signed.
2. **Registration** is under `Conduit.lifecycleLock`, with `ChannelState.refs` per custom channel. `PacketRegistry` refuses a wire name for two classes, or a class under two names.
3. **No swallowed errors**: send failures fail the future; transports catch handler exceptions so their delivery thread survives; every `catch` logs or propagates.
4. **Registry**: The first registered instance is `defaultInstance`; a duplicate registry name is refused. `Conduit.resetAll()` (package-private) is for tests.
5. **Metrics**: `ConduitMetrics`, made of `LongAdder`s; `byWire` only holds registered wire names.

## Testing Strategy

- `BaseConduitIntegrationTest` (in conduit-core tests, exported through the `testArtifacts` configuration) is the shared suite. Subclasses implement `newTransport()` and optionally `settle()`.
- It runs on `InMemoryConduitIntegrationTest` (sync), `AsyncInMemoryConduitIntegrationTest`, `ConduitRedisIntegrationTest` and `ConduitRabbitMQIntegrationTest`.
- Redis-only: `RedisTransportTest` (provided client, ACL `NOPERM`, prefix), `RedisConfigTest`, `DualClassLoaderRedisTest`.
- Test dependencies: JUnit Jupiter 6, AssertJ, Awaitility, Testcontainers.

## Maven Publishing

Published to `https://repo.groupez.dev/`. Credentials come from `MAVEN_USERNAME`/`MAVEN_PASSWORD` or Gradle properties.

## CI/CD

`.github/workflows/build.yml` runs on push/PR to main/develop and uses a reusable workflow from `GroupeZ-dev/actions`.
