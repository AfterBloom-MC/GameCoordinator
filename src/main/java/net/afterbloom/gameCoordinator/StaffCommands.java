package net.afterbloom.gameCoordinator;

import com.google.gson.JsonObject;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class StaffCommands implements CommandExecutor, TabCompleter {

    private final GameCoordinator plugin;

    public StaffCommands(GameCoordinator plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (label.equalsIgnoreCase("report")) {
            return handleReport(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command is for players only.");
            return true;
        }

        switch (label.toLowerCase()) {
            case "follow-player":
                return handleFollow(player, args);
            case "find":
                return handleFind(player, args);
            case "mgtp":
                return handleMgtp(player, args);
            case "staffmode":
                return handleStaffMode(player);
        }

        return true;
    }

    private boolean handleReport(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /report [player] [reason]");
            return true;
        }

        String target = args[0];
        StringBuilder reasonBuilder = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            reasonBuilder.append(args[i]).append(" ");
        }
        String reason = reasonBuilder.toString().trim();
        String reporter = sender.getName();
        String serverId = (plugin.getConfig().getBoolean("supporterMode", false) ? "supporter-" : "coord-") + plugin.getConfig().getString("serverId");
        
        // Send to Redis for the Coordinator to handle (Discord + Broadcast)
        JsonObject reportJson = new JsonObject();
        reportJson.addProperty("receiverId", "coord-*");
        reportJson.addProperty("senderId", serverId);
        reportJson.addProperty("function", "reportAlert");
        reportJson.addProperty("reporter", reporter);
        reportJson.addProperty("target", target);
        reportJson.addProperty("reason", reason);
        reportJson.addProperty("server", serverId);
        
        try {
            Redis.publish(plugin.getConfig().getString("discoverChannel"), reportJson.toString());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to publish report: " + e.getMessage());
        }

        sender.sendMessage("§aThank you for your report. Staff have been notified.");
        return true;
    }

    private boolean handleFollow(Player player, String[] args) {
        if (!player.hasPermission("minigames.staff.follow")) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§cUsage: /follow-player [player]");
            return true;
        }
        return attemptTeleport(player, args[0]);
    }

    private boolean handleFind(Player player, String[] args) {
        if (!player.hasPermission("minigames.staff.find")) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§cUsage: /find [player]");
            return true;
        }

        String target = args[0];
        String serverId = findOnlinePlayerServer(target);

        if (serverId != null) {
            player.sendMessage("§a" + target + " is currently online on §f" + serverId);
            TextComponent message = new TextComponent("§e[Click to teleport in vanish]");
            message.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mgtp " + target));
            player.spigot().sendMessage(message);
        } else {
            // Ideally we'd check a database for last seen here, but let's stick to network presence for now
            player.sendMessage("§c" + target + " is currently offline.");
        }
        return true;
    }

    private boolean handleMgtp(Player player, String[] args) {
        if (!player.hasPermission("minigames.staff")) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }
        if (args.length < 1) {
            player.sendMessage("§cUsage: /mgtp [player]");
            return true;
        }
        return attemptTeleport(player, args[0]);
    }

    private boolean handleStaffMode(Player player) {
        if (!player.hasPermission("minigames.staff")) {
            player.sendMessage("§cYou do not have permission.");
            return true;
        }
        VanishManager.toggleVanish(player);
        return true;
    }

    private boolean attemptTeleport(Player staff, String targetName) {
        String serverId = findOnlinePlayerServer(targetName);
        if (serverId == null) {
            staff.sendMessage("§cPlayer " + targetName + " not found on the network.");
            return false;
        }

        String localId = (plugin.getConfig().getBoolean("supporterMode", false) ? "supporter-" : "coord-") + plugin.getConfig().getString("serverId");
        
        if (serverId.equals(localId)) {
            Player target = Bukkit.getPlayer(targetName);
            if (target != null) {
                staff.teleport(target);
                VanishManager.vanish(staff);
                staff.sendMessage("§aTeleported to " + targetName + " in vanish.");
            } else {
                staff.sendMessage("§cPlayer vanished from local server.");
            }
        } else {
            // Cross-server teleport logic
            // 1. Move staff to the target server
            // In a real proxy environment, we'd send a plugin message to the proxy to move the player.
            // But since this is a "GameCoordinator", we'll assume we can initiate a move.
            // However, the issue description says "Lets staff... be teleported into servers with a player"
            // We'll use a Redis message to the target server to prepare for the staff arrival.
            
            JsonObject tpReq = new JsonObject();
            tpReq.addProperty("receiverId", serverId);
            tpReq.addProperty("senderId", localId);
            tpReq.addProperty("function", "teleportRequest");
            tpReq.addProperty("targetPlayer", targetName);
            tpReq.addProperty("staffPlayer", staff.getName());

            try {
                Redis.publish(plugin.getConfig().getString("discoverChannel"), tpReq.toString());
                staff.sendMessage("§eInitiating cross-server teleport to " + targetName + " on " + serverId + "...");
                // Note: The actual player movement between servers must be handled by the proxy.
                // We'll assume the staff uses a proxy command or we have a way to move them.
                // For now, we've sent the request so the target server can handle them when they arrive.
                staff.performCommand("server " + serverId.replace("coord-", "").replace("game-", ""));
            } catch (Exception e) {
                staff.sendMessage("§cFailed to send teleport request.");
            }
        }
        return true;
    }

    private String findOnlinePlayerServer(String name) {
        // Check local
        if (Bukkit.getPlayer(name) != null) {
            return (plugin.getConfig().getBoolean("supporterMode", false) ? "supporter-" : "coord-") + plugin.getConfig().getString("serverId");
        }
        // Check remote
        for (Map.Entry<String, Servers.ServerEntry> entry : Servers.getServers().entrySet()) {
            if (entry.getValue().getPlayers().stream().anyMatch(p -> p.equalsIgnoreCase(name))) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            if (partial.isEmpty()) return Collections.emptyList();

            List<String> players = new ArrayList<>();
            // Local
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    players.add(p.getName());
                }
            }
            // Remote
            for (Servers.ServerEntry server : Servers.getServers().values()) {
                for (String pName : server.getPlayers()) {
                    if (pName.toLowerCase().startsWith(partial) && !players.contains(pName)) {
                        players.add(pName);
                    }
                }
            }
            return players;
        }
        return Collections.emptyList();
    }
}
