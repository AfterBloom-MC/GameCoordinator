package net.afterbloom.gameCoordinator;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class VanishManager {
    private static final Set<UUID> vanishedPlayers = new HashSet<>();
    private static GameCoordinator plugin;

    public static void init(GameCoordinator mainPlugin) {
        plugin = mainPlugin;
    }

    public static void toggleVanish(Player player) {
        if (vanishedPlayers.contains(player.getUniqueId())) {
            unvanish(player);
        } else {
            vanish(player);
        }
    }

    public static void vanish(Player player) {
        vanishedPlayers.add(player.getUniqueId());
        player.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        player.setGameMode(GameMode.SPECTATOR);
        
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.hasPermission("minigames.staff")) {
                other.hidePlayer(plugin, player);
            }
        }
        player.sendMessage("§aYou are now vanished and in spectator mode.");
    }

    public static void unvanish(Player player) {
        vanishedPlayers.remove(player.getUniqueId());
        player.removeMetadata("vanished", plugin);
        player.setGameMode(GameMode.SURVIVAL);
        
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(plugin, player);
        }
        player.sendMessage("§cYou are no longer vanished.");
    }

    public static boolean isVanished(Player player) {
        return vanishedPlayers.contains(player.getUniqueId());
    }
    
    public static void handleJoin(Player player) {
        // Hide vanished players from the joining player if they aren't staff
        if (!player.hasPermission("minigames.staff")) {
            for (UUID uuid : vanishedPlayers) {
                Player vanished = Bukkit.getPlayer(uuid);
                if (vanished != null) {
                    player.hidePlayer(plugin, vanished);
                }
            }
        }
    }
}
