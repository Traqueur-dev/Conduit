package examples.basic;

import fr.traqueur.conduit.core.Conduit;
import fr.traqueur.conduit.core.PacketMeta;
import fr.traqueur.conduit.handler.HandlerResult;
import fr.traqueur.conduit.packet.Packet;
import fr.traqueur.conduit.redis.RedisConfig;
import fr.traqueur.conduit.redis.RedisTransport;

import java.util.UUID;

/**
 * Custom channel example.
 * Shows how to use @PacketMeta to route different packet types to different channels.
 */
public class CustomChannelExample {

    // Default channel packets
    public record SystemPacket(String systemEvent) implements Packet {}

    // Custom channel for player events
    @PacketMeta(channel = "player-events")
    public record PlayerJoinPacket(UUID playerId, String playerName) implements Packet {}

    @PacketMeta(channel = "player-events")
    public record PlayerLeavePacket(UUID playerId) implements Packet {}

    // Custom channel for game events
    @PacketMeta(channel = "game-events")
    public record GameStartPacket(String gameId, int playerCount) implements Packet {}

    @PacketMeta(channel = "game-events")
    public record GameEndPacket(String gameId, String winner) implements Packet {}

    public static void main(String[] args) throws Exception {
        // Setup two instances
        Conduit server1 = setupConduit("server-1");
        Conduit server2 = setupConduit("server-2");

        // Register all packet types on both instances
        registerPackets(server1);
        registerPackets(server2);

        // Register handlers on server2
        server2.registerHandler(SystemPacket.class, (packet, ackCallback) -> {
            System.out.println("[SYSTEM] " + packet.systemEvent());
            return HandlerResult.SUCCESS;
        });

        server2.registerHandler(PlayerJoinPacket.class, (packet, ackCallback) -> {
            System.out.println("[PLAYER] " + packet.playerName() + " joined");
            return HandlerResult.SUCCESS;
        });

        server2.registerHandler(PlayerLeavePacket.class, (packet, ackCallback) -> {
            System.out.println("[PLAYER] Player " + packet.playerId() + " left");
            return HandlerResult.SUCCESS;
        });

        server2.registerHandler(GameStartPacket.class, (packet, ackCallback) -> {
            System.out.println("[GAME] Game " + packet.gameId() + " started with " + packet.playerCount() + " players");
            return HandlerResult.SUCCESS;
        });

        server2.registerHandler(GameEndPacket.class, (packet, ackCallback) -> {
            System.out.println("[GAME] Game " + packet.gameId() + " ended. Winner: " + packet.winner());
            return HandlerResult.SUCCESS;
        });

        // Start both instances
        server1.start();
        server2.start();

        Thread.sleep(2000);

        // Send packets on different channels
        System.out.println("Sending packets on different channels...\n");

        // System events (default channel)
        new SystemPacket("Server initialized").send();
        Thread.sleep(500);

        // Player events (player-events channel)
        new PlayerJoinPacket(UUID.randomUUID(), "Alice").send();
        Thread.sleep(500);
        new PlayerJoinPacket(UUID.randomUUID(), "Bob").send();
        Thread.sleep(500);

        // Game events (game-events channel)
        new GameStartPacket("game-123", 2).send();
        Thread.sleep(2000);
        new GameEndPacket("game-123", "Alice").send();
        Thread.sleep(500);

        // Player leaves
        new PlayerLeavePacket(UUID.randomUUID()).send();
        Thread.sleep(1000);

        // Cleanup
        server1.shutdown();
        server2.shutdown();
    }

    private static void registerPackets(Conduit conduit) {
        conduit.registerPacket(SystemPacket.class);
        conduit.registerPacket(PlayerJoinPacket.class);
        conduit.registerPacket(PlayerLeavePacket.class);
        conduit.registerPacket(GameStartPacket.class);
        conduit.registerPacket(GameEndPacket.class);
    }

    private static Conduit setupConduit(String instanceId) {
        RedisConfig config = RedisConfig.localhost();
        RedisTransport transport = new RedisTransport(config);

        return Conduit.builder()
                .transport(transport)
                .instanceId(instanceId)
                .build();
    }
}
