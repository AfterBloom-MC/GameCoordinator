package net.afterbloom.gameCoordinator;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.List;

public class Servers {
    private static final Map<String, ServerEntry> servers = new ConcurrentHashMap<>();

    public static void newServer(String serverId, String serverGame, int playerCount, int lobbyCount) {
        ServerEntry entry = servers.get(serverId);
        if (entry == null) {
            servers.put(serverId, new ServerEntry(serverId, serverGame, playerCount, lobbyCount));
            GameCoordinator.getLoggerInstance().info("[Servers] Registered new server: " + serverId + " (" + serverGame + ") with " + playerCount + " players and " + lobbyCount + " lobbies.");
        } else {
            entry.update(playerCount, lobbyCount);
        }
    }

    public static ServerEntry getServer(String serverId) {
        return servers.get(serverId);
    }

    public static Map<String, ServerEntry> getServers() {
        return servers;
    }

    public static void prune(long maxAgeSeconds) {
        long cutoff = Instant.now().getEpochSecond() - maxAgeSeconds;
        servers.entrySet().removeIf(e -> e.getValue().getLastSeen() < cutoff);
    }

    public static Map<String, ServerEntry> getServersByGame(String serverGame) {
        return servers.entrySet()
                .stream()
                .filter(e -> e.getValue().getServerGame().equalsIgnoreCase(serverGame))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    public static List<ServerEntry> getServersByGameSorted(String game) {
        return getServersByGame(game).values()
                .stream()
                .sorted((a, b) -> a.getServerId().compareToIgnoreCase(b.getServerId()))
                .toList();
    }

    public static class ServerEntry {
        private final String serverId;
        private final String serverGame;
        private volatile long lastSeen;
        private volatile int playerCount;
        private volatile int lobbyCount;

        public ServerEntry(String serverId, String serverGame, int playerCount, int lobbyCount) {
            this.serverId = serverId;
            this.serverGame = serverGame;
            this.playerCount = playerCount;
            this.lobbyCount = lobbyCount;
            this.lastSeen = Instant.now().getEpochSecond();
        }

        public String getServerId() {
            return serverId;
        }

        public String getServerGame() {
            return serverGame;
        }

        public long getLastSeen() {
            return lastSeen;
        }

        public int getPlayerCount() {
            return playerCount;
        }

        public int getLobbyCount() {
            return lobbyCount;
        }

        private void update(int playerCount, int lobbyCount) {
            this.playerCount = playerCount;
            this.lobbyCount = lobbyCount;
            this.lastSeen = Instant.now().getEpochSecond();
        }

        private void refreshHeartbeat() {
            this.lastSeen = Instant.now().getEpochSecond();
        }
    }
}
