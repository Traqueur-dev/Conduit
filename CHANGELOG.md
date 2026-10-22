# Changelog

## 2.0.0 — 2026-10-22

A major version: the wire format, the `Transport` interface and several signatures change, with
no compatibility layer. **Every instance on a bus must move to 2.0.0 together** (a 1.x and a 2.x
instance cannot read each other's envelopes).

### Added

- **Explicit wire names.** `@PacketMeta(wire = "shop.rotation.v1")` and
  `registerPacket(String wireName, Class)`. Without one, the class simple name is used.
  A wire name already used by another class is refused with an `IllegalStateException` naming
  both classes; so is a class registered under a second name. Registering the same class again
  does nothing. `ConduitBuilder.requireWireNames()` refuses any type without an explicit name.
  `@PacketMeta.channel` is now optional (empty means the default channel).
- **Hot registration.** `registerPacket` after `start()` subscribes the type's channel (broadcast
  and unicast) right away. `unregisterPacket(Class)` removes the type and its handler, and
  unsubscribes the channel once no registered type uses it. Channels are reference-counted, and
  the default channel is never unsubscribed. `unregisterHandler(Class)` is exposed. Both
  `registerPacket` and `unregisterPacket` return a `CompletableFuture<Void>` that completes once
  the subscription change is in effect. All of this is safe under concurrent reception.
- **Handler executor.** `ConduitBuilder.handlerExecutor(Executor)`: every handler runs on it, while
  decoding stays on the transport thread. A refused task drops the packet: it is logged, counted
  in `handlerFailures`, and NACKed if it required an ACK. Order is kept with a single-threaded
  executor. The default is still the transport thread.
- **In-memory transport.** `fr.traqueur.conduit.memory.InMemoryBus` in `conduit-core`, with no
  dependency. Every `bus.transport()` sees the others with Redis semantics: broadcast, unicast,
  ACKs, custom channels, prefix. Delivery is synchronous by default (the handler has run and the
  ACK is in when `send` returns); `InMemoryBus.async()` delivers in order on a background thread.
  Failure simulation: `disconnect(t)`/`reconnect(t)`, `dropNext(n)`, `dropAcks(n)`. The whole
  shared integration suite runs on both modes, as well as on Redis and RabbitMQ.
- **Signed envelopes.** `ConduitBuilder.signer(EnvelopeSigner)`, `.verifier(EnvelopeVerifier)`,
  `.maxClockSkew(Duration)` (default 30 s) and `.clock(Clock)`. `HmacSigner` and `HmacVerifier`
  provide HMAC-SHA256 from `javax.crypto`; `EnvelopeVerifier.and(EnvelopePolicy)` adds the
  application's rules. Once a verifier is configured, an envelope is refused if it is unsigned,
  badly signed, outside the clock skew, a replay, built for another channel, prefix or target, or
  refused by the policy. A refused envelope is counted, gets no NACK, and is logged at most once
  per sender per minute. ACK responses are signed and verified too. See
  [docs/ENVELOPE.md](docs/ENVELOPE.md).
- **Metrics.** `conduit.metrics()`: `sent`, `received`, `sendFailures`, `handlerFailures`,
  `refused`, `ackTimeouts`, `unknownType`, plus `byWire()` per registered wire name. They are
  `LongAdder`s, read without locking, and pull in no dependency.
- **Unregistered instances.** `ConduitBuilder.unregistered()` keeps an instance out of the static
  registry: it is never the default and never found by name.
- **RabbitMQ.** New constructors `RabbitMQTransport(ConnectionFactory)` and
  `RabbitMQTransport(Connection)`, and `RabbitMQTransport.builder()` with `config`,
  `connectionFactory`, `connection` and `channelPrefix`. A provided connection is never closed by
  the transport; only its own channels and queues are. The prefix starts every exchange and queue
  name, so one RabbitMQ permission regex (`^prefix:.*`) confines a user to one environment.
  Publishing uses publisher confirms: the future completes on the broker's confirmation, and fails
  on a nack, a refused permission (`ACCESS_REFUSED`) or a closed channel.
  Each subscription has its own channel, and declarations run on a short-lived one, so a refusal
  never closes the other subscriptions. A closed publish channel is reopened.
  `RabbitMQConfig.toString()` hides the password.
- **Redis.** New constructors `RedisTransport(RedisURI)` and `RedisTransport(RedisClient)`, and
  `RedisTransport.builder()` with `config`, `uri`, `client` and `channelPrefix`. A provided client,
  and its `ClientResources`, is never closed by the transport. `channelPrefix` applies to every
  channel (broadcast, unicast, ACK) and is signed. `RedisConfig` gains `username` (ACL) and
  `timeout`, and a builder.
- `Conduit.instanceId()`, `defaultChannel()`, `transport()` and `isStarted()`.

### Changed

- `send`, `sendTo`, `Packet.send()` and `TargetablePacket.sendTo()` return
  `CompletableFuture<Void>`. The future completes once the transport published, and fails on an
  unregistered type, a serialization error, a disconnected transport or a refusal by the backend
  (including Redis ACL `NOPERM`). Failures are no longer only logged.
- `Transport` is redesigned around futures and opaque bytes. `broadcast` and `sendTo` return
  `CompletableFuture<Void>`. `subscribe*` and `unsubscribe*` return futures, and
  `unsubscribeUnicast` and `channelPrefix()` are new. `broadcastWithAck`, `sendToWithAck` and
  `sendAckResponse` are gone: ACK routing now lives in `Conduit`, which works the same way on
  every transport.
- **ACKs** travel on one reply channel per instance, `<defaultChannel>:ack`, subscribed once at
  start, instead of a temporary subscription (Redis) or reply queue (RabbitMQ) per request.
  Measured on Redis 7, request plus ACK round-trip: sequential 1025 → ~895 µs; 2000 concurrent
  requests 585 → ~250 ms. That channel name is reserved.
- `start()` returns once every subscription is active (no more sleeping before the first send).
  It throws if called twice or after `shutdown()`.
- `shutdown()` fails pending ACK futures instead of letting them time out.
- A packet of an unknown or removed type is logged once at `debug` (it used to be logged at
  `error` every time) and NACKed when it asks for an ACK (it used to time out).
- A handler's exception reaches the requester as a failed ACK carrying its message, and is counted.
- Two instances under the same registry name are refused (the second used to replace the first
  silently).
- `RedisTransport` routes every message through a single listener. Unsubscribing removes the
  route; the per-subscription listeners used to accumulate.
- `RabbitMQTransport.unsubscribe` works for broadcast channels too, and an exchange declaration
  that fails is retried instead of being cached. Broadcast queues are now named
  (`<prefix>conduit.sub.<channel>.<random>`) instead of server-named.
- Lettuce 7.2.0 → 7.8.0.

### Removed

- The 1.x envelope format and `PacketEnvelope`'s metadata map, `toBytes()` and `fromBytes()`
  (replaced by `encode(signer)` and `decode(bytes)`).
- `RedisConfig`'s positional constructors other than the canonical one, and `RedisConfig.of(host,
  port, password[, ssl[, verifyPeer]])`. Use `RedisConfig.builder(host, port)`.
- `RedisConfig.toUri()`, which put the password in clear in a string.
- `RedisAckListener` and `RedisMessageListener`.

### Migrating from 1.3.0

| 1.3.0                                                  | 2.0.0                                                                  |
|--------------------------------------------------------|------------------------------------------------------------------------|
| `new RedisConfig(host, port, password, db)`            | `RedisConfig.builder(host, port).password(password).database(db).build()` |
| `RedisConfig.of(host, port, password, true)`           | `RedisConfig.builder(host, port).password(password).ssl().build()`     |
| `conduit.send(packet);` (void)                         | unchanged in source; the returned future can now be checked             |
| custom `Transport`                                     | implement the new interface; drop all ACK logic                        |
| `Thread.sleep(...)` after `start()`                    | not needed                                                             |
| `BaseConduitIntegrationTest.setupConduitInstances()`   | `newTransport(String channelPrefix)` (one fresh transport on the shared backend) |
| RabbitMQ exchanges `conduit.broadcast.<ch>`            | unchanged without prefix; `<prefix>conduit.broadcast.<ch>` with one     |
