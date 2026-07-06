package net.afterbloom.gameCoordinator;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class GameCoordinatorPlaceholders extends PlaceholderExpansion {

    private final GameCoordinator plugin;

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

        if (params.equals("total_players")) {
            return String.valueOf(Servers.getServers().values().stream()
                    .mapToInt(Servers.ServerEntry::getPlayerCount)
                    .sum());
        }

        if (params.startsWith("players_")) {
            String gameType = params.substring(8);
            return String.valueOf(Servers.getServersByGame(gameType).values().stream()
                    .mapToInt(Servers.ServerEntry::getPlayerCount)
                    .sum());
        }

        if (params.startsWith("servers_")) {
            String gameType = params.substring(8);
            return String.valueOf(Servers.getServersByGame(gameType).size());
        }

        if (params.startsWith("lobbies_")) {
            String gameType = params.substring(8);
            return String.valueOf(Servers.getServersByGame(gameType).values().stream()
                    .mapToInt(Servers.ServerEntry::getLobbyCount)
                    .sum());
        }

        // New Playtime Placeholders
        // %gamecoordinator_playtime_total% -> Player's total playtime across all games
        if (params.equals("playtime_total")) {
            double total = StatsStorage.getStat(player.getUniqueId().toString(), "global", "playtime", StatsStorage.Slice.TOTAL);
            return formatPlaytime(total);
        }

        // %gamecoordinator_playtime_<gametype>% -> Player's playtime in specific game
        if (params.startsWith("playtime_")) {
            String gameType = params.substring(9);
            double val = StatsStorage.getStat(player.getUniqueId().toString(), gameType, "playtime", StatsStorage.Slice.TOTAL);
            return formatPlaytime(val);
        }

        return null; // Placeholder not found
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
