package net.afterbloom.gameCoordinator;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class Servers {
    private static final Map<String, ServerEntry> servers = new ConcurrentHashMap<>();

    public static void newServer(String serverId, String serverGame) {
        ServerEntry entry = servers.get(serverId);
        if (entry == null) {
            servers.put(serverId, new ServerEntry(serverId, serverGame));
            Logger logger = GameCoordinator.getLoggerInstance();
            logger.info("[Servers] Registered new server: " + serverId + " (" + serverGame + ")");
        } else {
            entry.refreshHeartbeat();
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

    public static class ServerEntry {
        private final String serverId;
        private final String serverGame;
        private volatile long lastSeen;

        public ServerEntry(String serverId, String serverGame) {
            this.serverId = serverId;
            this.serverGame = serverGame;
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

        private void refreshHeartbeat() {
            this.lastSeen = Instant.now().getEpochSecond();
        }
    }
}
