# Conduit envelope — format version 2

Every message Conduit puts on a transport, packet or acknowledgment, is one envelope. Since 2.0.0
the format is versioned. A 1.x instance cannot read it, and a 2.x instance refuses a 1.x envelope
(its first byte, a flags byte in `0x00..0x03`, is read as an unknown version). Upgrade every
instance on a bus together.

## Layout

All integers are big-endian. `str` is an `int32` length followed by that many UTF-8 bytes; a
length of `-1` means `null`. `bytes` is an `int32` length followed by the bytes.

| Field          | Type    | Meaning                                                                 |
|----------------|---------|-------------------------------------------------------------------------|
| `version`      | `u8`    | always `0x02`                                                           |
| `flags`        | `u8`    | bit 0 `requiresAck`, bit 1 `ackResponse`, bit 2 `signed`; others 0      |
| `timestamp`    | `i64`   | sender clock, epoch milliseconds                                        |
| `packetId`     | `str`   | unique per envelope (random UUID); anti-replay key; ACK id of a request |
| `senderId`     | `str`   | `instanceId` of the sender                                              |
| `namespace`    | `str`   | the transport's channel prefix (`""` when none)                         |
| `channel`      | `str`   | logical channel the envelope is published on                            |
| `target`       | `str`   | unicast target `instanceId`; `null` for a broadcast                     |
| `wireName`     | `str`   | packet type name; `null` for an ACK response                            |
| `replyChannel` | `str`   | where the sender listens for ACKs (`<defaultChannel>:ack`); `null` unless `requiresAck` |
| `ackId`        | `str`   | ACK response only: `packetId` of the request it answers                 |
| `keyId`        | `str`   | signer key id; present exactly when `signed`                            |
| `success`      | `u8`    | ACK response only                                                       |
| `message`      | `str`   | ACK response only                                                       |
| `ackTimestamp` | `i64`   | ACK response only, epoch milliseconds                                   |
| `payload`      | `bytes` | serialized, then compressed packet; empty for an ACK response           |
| `signature`    | `bytes` | signed only: signature of every byte above, from `version` to `payload` |

An envelope has no free-form metadata. Transports no longer write anything into it, so every byte
except the signature is covered by the signature.

Decoding checks every length against the bytes that remain. It refuses trailing bytes, a
`signed` flag that disagrees with `keyId`, and a missing `packetId`, `senderId`, `channel`,
`wireName` (packets) or `ackId` (ACK responses).

## Routing

| Message       | Redis channel                                  | RabbitMQ                                                  |
|---------------|------------------------------------------------|-----------------------------------------------------------|
| broadcast     | `<prefix><channel>`                            | fanout exchange `<prefix>conduit.broadcast.<channel>`, one queue `<prefix>conduit.sub.<channel>.<random>` per subscriber |
| unicast       | `<prefix><channel>:<target>`                   | direct exchange `<prefix>conduit.direct.<channel>`, key `<target>`, queue `<prefix>conduit.unicast.<channel>.<target>` |
| ACK response  | `<prefix><defaultChannel>:ack:<requester>`     | direct exchange `<prefix>conduit.direct.<defaultChannel>:ack`, key `<requester>` |

Every physical name starts with the prefix on both transports. A backend user can therefore be
confined to one prefix: a Redis ACL channel pattern `&valkn-prod:*`, or a RabbitMQ permission
regex `^valkn-prod:.*`.

Each instance subscribes its reply channel once, at `start()`. An ACK request costs one publish,
with no per-request subscription.

## What a receiver checks, in order

1. **Decoding.** Malformed bytes or another version are refused.
2. **Own broadcast.** A broadcast whose `senderId` is this instance is ignored (not counted).
3. **Routing**, always, even without a verifier:
   - `namespace` + `channel` must be exactly the prefix and channel the envelope arrived on;
   - `target` must be `null` on a broadcast, and this instance on a unicast;
   - ACK responses are only accepted on the reply channel, and only ACK responses are.
4. **Authentication**, only when a verifier is configured:
   - an unsigned envelope is refused;
   - the verifier gets `(keyId, signed bytes, signature, envelope)` and returns `Accepted` or
     `Refused(reason)`;
   - `|now − timestamp|` must not exceed `maxClockSkew` (30 s by default);
   - `keyId + packetId` must not have been accepted in the last `maxClockSkew`. That time window
     is exactly how long a replay would still pass the timestamp check.
5. **Type.** An unknown `wireName` is counted, logged once at `debug`, and NACKed if it asked
   for an ACK.

A refused envelope is never handed to a handler and never answered. It is counted in
`metrics().refused()` and logged with its reason at most once per minute per sender. A refused
ACK response leaves the request waiting for a genuine answer until its timeout. An ACK for a
unicast request is accepted only from the target the request was sent to.

Because the signature covers `namespace`, `channel` and `target`, a signed envelope captured on
one channel, prefix or target cannot be replayed on another. Because it covers `packetId` and
`timestamp`, it cannot be replayed on the same one either.

## Signing

`HmacSigner.sha256(keyId, secret)` and `HmacVerifier.sha256(Map<keyId, secret>)` use HMAC-SHA256
from `javax.crypto`. Signatures are compared in constant time, and secrets never appear in a
`toString()`. The application's rules go on top of the verifier:

```java
EnvelopeVerifier verifier = HmacVerifier.sha256(keys).and((keyId, env) ->
        !"si:command".equals(env.wireName()) || "reconciler".equals(keyId)
                ? Verdict.accept()
                : Verdict.refuse(keyId + " may not send si:command"));
```
