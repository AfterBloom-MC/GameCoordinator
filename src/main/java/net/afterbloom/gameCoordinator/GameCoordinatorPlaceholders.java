package net.afterbloom.gameCoordinator;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GameCoordinatorPlaceholders extends PlaceholderExpansion {

    private final GameCoordinator plugin;
    private static final long CACHE_TTL_MS = 5000;

    // Cache for global/game stats (total_players, players_x, servers_x, lobbies_x)
    private final Map<String, CacheEntry<String>> globalCache = new ConcurrentHashMap<>();
    
    // Cache for player-specific stats (playtime_total, playtime_x)
    // Map<PlayerUUID, Map<PlaceholderParam, CacheEntry>>
    private final Map<String, Map<String, CacheEntry<String>>> playerCache = new ConcurrentHashMap<>();

    private static class CacheEntry<T> {
        final T value;
        final long expiry;

        CacheEntry(T value, long expiry) {
            this.value = value;
            this.expiry = expiry;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    public GameCoordinatorPlaceholders(GameCoordinator plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "gamecoordinator";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true; // This is required or else PlaceholderAPI will unregister it on reload
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";

        // Check global cache first for non-player specific placeholders
        if (params.equals("total_players") || params.startsWith("players_") || params.startsWith("servers_") || params.startsWith("lobbies_")) {
            CacheEntry<String> entry = globalCache.get(params);
            if (entry != null && !entry.isExpired()) {
                return entry.value;
            }
            
            String result = computeGlobalPlaceholder(params);
            if (result != null) {
                globalCache.put(params, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MS));
            }
            return result;
        }

        // Player specific placeholders (playtime)
        if (params.equals("playtime_total") || params.startsWith("playtime_")) {
            String uuid = player.getUniqueId().toString();
            Map<String, CacheEntry<String>> pCache = playerCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
            CacheEntry<String> entry = pCache.get(params);
            if (entry != null && !entry.isExpired()) {
                return entry.value;
            }

            String result = computePlayerPlaceholder(player, params);
            if (result != null) {
                pCache.put(params, new CacheEntry<>(result, System.currentTimeMillis() + CACHE_TTL_MS));
            }
            return result;
        }

        return null; // Placeholder not found
    }

    private String computeGlobalPlaceholder(String params) {
        String localGame = plugin.getConfig().getString("serverGame");
        int localPlayerCount = plugin.getServer().getOnlinePlayers().size();

        if (params.equals("total_players")) {
            int remotePlayers = Servers.getServers().values().stream()
                    .mapToInt(Servers.ServerEntry::getPlayerCount)
                    .sum();
            return String.valueOf(remotePlayers + localPlayerCount);
        }

        if (params.startsWith("players_")) {
            String gameType = params.substring(8);
            int remotePlayers = Servers.getServersByGame(gameType).values().stream()
                    .mapToInt(Servers.ServerEntry::getPlayerCount)
                    .sum();
            if (gameType.equalsIgnoreCase(localGame)) {
                remotePlayers += localPlayerCount;
            }
            return String.valueOf(remotePlayers);
        }

        if (params.startsWith("servers_")) {
            String gameType = params.substring(8);
            int count = Servers.getServersByGame(gameType).size();
            if (gameType.equalsIgnoreCase(localGame)) {
                count++;
            }
            return String.valueOf(count);
        }

        if (params.startsWith("lobbies_")) {
            String gameType = params.substring(8);
            int count = Servers.getServersByGame(gameType).values().stream()
                    .mapToInt(Servers.ServerEntry::getLobbyCount)
                    .sum();
            if (gameType.equalsIgnoreCase(localGame)) {
                count++;
            }
            return String.valueOf(count);
        }
        return null;
    }

    private String computePlayerPlaceholder(OfflinePlayer player, String params) {
        if (params.equals("playtime_total")) {
            double total = StatsStorage.getStat(player.getUniqueId().toString(), "global", "playtime", StatsStorage.Slice.TOTAL);
            return formatPlaytime(total);
        }

        if (params.startsWith("playtime_")) {
            String gameType = params.substring(9);
            double val = StatsStorage.getStat(player.getUniqueId().toString(), gameType, "playtime", StatsStorage.Slice.TOTAL);
            return formatPlaytime(val);
        }
        return null;
    }

    private String formatPlaytime(double minutes) {
        long totalMinutes = (long) minutes;
        long hours = totalMinutes / 60;
        long remainingMinutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + remainingMinutes + "m";
        } else {
            return remainingMinutes + "m";
        }
    }
}
