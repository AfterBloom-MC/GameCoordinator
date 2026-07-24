package net.afterbloom.gameCoordinator;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.List;

public class Servers {
    private static final Map<String, ServerEntry> servers = new ConcurrentHashMap<>();
    private static volatile int networkTotalPlayers = 0;
    private static volatile Map<String, GameStats> gameStats = new HashMap<>();
    private static volatile long lastNetworkTotalUpdate = 0;

    public static class GameStats {
        public final int players;
        public final int servers;
        public final int lobbies;

        public GameStats(int players, int servers, int lobbies) {
            this.players = players;
            this.servers = servers;
            this.lobbies = lobbies;
        }
    }

    public static void updateGlobalStats(int total, Map<String, GameStats> stats) {
        networkTotalPlayers = total;
        gameStats = stats;
        lastNetworkTotalUpdate = System.currentTimeMillis();
    }

    public static GameStats getGameStats(String game) {
        return gameStats.getOrDefault(game.toLowerCase(), new GameStats(0, 0, 0));
    }

    public static void setNetworkTotalPlayers(int total, long requestId) {
        if (requestId >= lastNetworkTotalUpdate) {
            networkTotalPlayers = total;
            lastNetworkTotalUpdate = requestId;
        }
    }

    public static int getNetworkTotalPlayers() {
        return networkTotalPlayers;
    }

    public static void newServer(String serverId, String serverGame, int playerCount, int lobbyCount, List<String> players) {
        ServerEntry entry = servers.get(serverId);
        if (entry == null) {
            servers.put(serverId, new ServerEntry(serverId, serverGame, playerCount, lobbyCount, players));
            GameCoordinator.getLoggerInstance().info("[Servers] Registered new server: " + serverId + " (" + serverGame + ") with " + playerCount + " players and " + lobbyCount + " lobbies.");
        } else {
            entry.update(playerCount, lobbyCount, players);
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
        private volatile List<String> players;

        public ServerEntry(String serverId, String serverGame, int playerCount, int lobbyCount, List<String> players) {
            this.serverId = serverId;
            this.serverGame = serverGame;
            this.playerCount = playerCount;
            this.lobbyCount = lobbyCount;
            this.players = players != null ? players : Collections.emptyList();
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

        public List<String> getPlayers() {
            return players;
        }

        private void update(int playerCount, int lobbyCount, List<String> players) {
            this.playerCount = playerCount;
            this.lobbyCount = lobbyCount;
            this.players = players != null ? players : Collections.emptyList();
            this.lastSeen = Instant.now().getEpochSecond();
        }

        private void refreshHeartbeat() {
            this.lastSeen = Instant.now().getEpochSecond();
        }
    }
}
