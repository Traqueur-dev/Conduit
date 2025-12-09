# Conduit Documentation Index

Complete documentation for the Conduit messaging library.

## Getting Started

1. **[QUICKSTART.md](QUICKSTART.md)** ⚡
   - 5-minute quick start guide
   - Minimal working example
   - Essential patterns
   - Start here if you're new!

2. **[README.md](README.md)** 📚
   - Complete feature overview
   - Installation instructions
   - All usage patterns and configurations
   - Best practices and architecture

3. **[Examples](examples/README.md)** 💡
   - Step-by-step examples with explanations
   - Basic and advanced patterns
   - Real-world use cases
   - Troubleshooting guide

## Core Documentation

### User Guides

- **QUICKSTART.md** - Get started in 5 minutes
- **README.md** - Complete user guide
- **examples/README.md** - Example documentation

### Developer Guides

- **CLAUDE.md** - Architecture and development guide
  - Build commands
  - Testing strategy
  - Implementation notes
  - CI/CD information

### API Reference

- **Javadoc** - Generated API documentation
  - Location: `build/docs/javadoc/`
  - Generate: `./gradlew javadoc`

## Example Code

### Basic Examples (examples/basic/)

1. **SimpleBroadcastExample.java**
   - Basic broadcast messaging
   - Message sending and receiving
   - Handler registration

2. **UnicastExample.java**
   - Direct instance-to-instance messaging
   - TargetablePacket usage
   - Instance targeting

3. **AcknowledgmentExample.java**
   - Request-response pattern
   - ACK handling
   - Timeout configuration

4. **CustomChannelExample.java**
   - Channel-based routing
   - @PacketMeta annotation
   - Topic organization

### Advanced Examples (examples/advanced/)

1. **MultiTransportExample.java**
   - Redis and RabbitMQ comparison
   - Transport isolation
   - Multi-transport setup

2. **CompressionExample.java**
   - Performance comparison
   - Gzip compression
   - When to compress

3. **LoadBalancingExample.java**
   - Work distribution
   - Round-robin balancing
   - Distributed processing

4. **PubSubPatternExample.java**
   - Event-driven architecture
   - Publisher-subscriber pattern
   - Real-world event flow

## Quick Reference

### Common Tasks

| Task                      | Documentation                                                        |
|---------------------------|----------------------------------------------------------------------|
| First time setup          | [QUICKSTART.md](QUICKSTART.md)                                       |
| Send broadcast message    | [README.md#broadcast-messaging](README.md#broadcast-messaging)       |
| Send to specific instance | [README.md#unicast-messaging](README.md#unicast-messaging)           |
| Request acknowledgment    | [README.md#acknowledgment-support](README.md#acknowledgment-support) |
| Use custom channels       | [README.md#custom-channels](README.md#custom-channels)               |
| Enable compression        | [README.md#compression](README.md#compression)                       |
| Configure Redis           | [README.md#redis](README.md#redis)                                   |
| Configure RabbitMQ        | [README.md#rabbitmq](README.md#rabbitmq)                             |
| Build from source         | [CLAUDE.md](CLAUDE.md)                                               |
| Run tests                 | [CLAUDE.md](CLAUDE.md)                                               |

### By Use Case

| Use Case            | Example                |
|---------------------|------------------------|
| Chat application    | SimpleBroadcastExample |
| Task distribution   | LoadBalancingExample   |
| Event-driven system | PubSubPatternExample   |
| Request-response    | AcknowledgmentExample  |
| Private messaging   | UnicastExample         |
| Topic-based routing | CustomChannelExample   |

### By Feature

| Feature         | Documentation                     |
|-----------------|-----------------------------------|
| Broadcast       | SimpleBroadcastExample, README.md |
| Unicast         | UnicastExample, README.md         |
| Acknowledgments | AcknowledgmentExample, README.md  |
| Custom Channels | CustomChannelExample, README.md   |
| Compression     | CompressionExample, README.md     |
| Load Balancing  | LoadBalancingExample              |
| Pub-Sub         | PubSubPatternExample              |
| Multi-Transport | MultiTransportExample             |

## Documentation by Audience

### For New Users

1. Read [QUICKSTART.md](QUICKSTART.md)
2. Try [SimpleBroadcastExample](examples/basic/SimpleBroadcastExample.java)
3. Explore [examples/README.md](examples/README.md)
4. Reference [README.md](README.md) as needed

### For Developers

1. Study [examples/advanced/](examples/advanced/)
2. Read test suite in `src/test/`
3Generate Javadoc: `./gradlew javadoc`

### For System Architects

1. Read [README.md#architecture](README.md#architecture)
2. Study [PubSubPatternExample](examples/advanced/PubSubPatternExample.java)
3. Review [LoadBalancingExample](examples/advanced/LoadBalancingExample.java)
4. Compare transports in [MultiTransportExample](examples/advanced/MultiTransportExample.java)

## Building Documentation

### Generate Javadoc

```bash
./gradlew javadoc
```

Output: `build/docs/javadoc/index.html`

### Build Project

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

## Contributing

See main [README.md](README.md#contributing) for contribution guidelines.

---

**Need help?** Start with [QUICKSTART.md](QUICKSTART.md) or check [examples/](examples/)!
