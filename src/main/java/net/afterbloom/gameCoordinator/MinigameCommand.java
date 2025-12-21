package net.afterbloom.gameCoordinator;

import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MinigameCommand implements CommandExecutor, TabCompleter {

    private static JavaPlugin plugin;

    public MinigameCommand(GameCoordinator plugin) {
        MinigameCommand.plugin = plugin;
    }

    public static void tryNextServer(String playerName, String game) {
        Servers.ServerEntry server = Utils.nextServer(playerName);
        if (server == null) {
            GameCoordinator.getLoggerInstance().info(
                    "No servers with space left for player " + playerName + " in game " + game
            );
            // Notify player and clear pending queue
            org.bukkit.entity.Player p = MinigameCommand.plugin.getServer().getPlayerExact(playerName);
            if (p != null) {
                p.sendMessage("[Minigames] No available servers found for " + game + ". Please try again later.");
            }
            Utils.clearPending(playerName);
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("receiverId", server.getServerId());
        json.addProperty("senderId", "coord-" + MinigameCommand.plugin.getConfig().getString("serverId"));
        json.addProperty("function", "findServer");
        json.addProperty("player", playerName);

        // Publish to Redis on the game channel
        String gameChannel = MinigameCommand.plugin.getConfig().getString("gameChannel");
        Redis.publish(gameChannel, json.toString());
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (args.length == 0) {
            sender.sendMessage("Available minigames: /minigame list");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list":
                sender.sendMessage("Available minigames:");
                // Loop through available game servers
                Servers.getServers().forEach((id, server) -> {
                    sender.sendMessage("- " + server.getServerGame() + " (" + id + ")");
                });
                return true;

            case "join":
                if (args.length < 2) {
                    sender.sendMessage("Usage: /minigame join <game>");
                    return true;
                }

                String game = args[1];
                String playerName = sender.getName(); // or use args[2] if supporting other players

                // Get servers sorted alphabetically/numerically
                List<Servers.ServerEntry> servers = Servers.getServersByGameSorted(game);
                if (servers.isEmpty()) {
                    sender.sendMessage("No servers available for game: " + game);
                    return true;
                }

                // Initialize pending joins queue for this player and game
                Utils.initPending(playerName, game, servers);

                // Try first server
                tryNextServer(playerName, game);

                return true;

            default:
                sender.sendMessage("Error: Unknown arguments. Please use /minigame help for list of commands");
                return true;

        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {

        if (args.length == 1) {
            List<String> subcommands = List.of("list", "join");
            String partial = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : subcommands) {
                if (s.startsWith(partial)) out.add(s);
            }
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            // Tab complete with available game types
            List<String> games = new ArrayList<>();
            Servers.getServers().forEach((id, server) -> {
                String game = server.getServerGame();
                if (!games.contains(game)) games.add(game);
            });
            return games;
        }

        return Collections.emptyList();
    }
}