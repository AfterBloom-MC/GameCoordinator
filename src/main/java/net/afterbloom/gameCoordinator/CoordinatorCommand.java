package net.afterbloom.gameCoordinator;

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
import java.util.logging.Logger;

public class CoordinatorCommand implements CommandExecutor, TabCompleter {

    private static JavaPlugin plugin;
    private static final Logger logger = GameCoordinator.getLoggerInstance();

    public CoordinatorCommand(GameCoordinator plugin) {
        CoordinatorCommand.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!sender.hasPermission("minigames.manage")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("For more info use: /coordinator help");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                sender.sendMessage("Game Coordinator help:\n" +
                        "/coordinator help - show this message\n" +
                        "/coordinator testpub - sends a message on the 'test' channel\n" +
                        "/coordinator testsub - toggles a listener on the 'test' channel");
                return true;

            case "testpub":
                try {
                    Redis.publish("test", "This is a test message from the GameCoordinator server!");
                    sender.sendMessage("Redis test message sent!");
                } catch (Exception e) {
                    sender.sendMessage("Redis test failed! " + e.getMessage());
                    logger.severe("Redis test failed: " + e.getMessage());
                }
                return true;

            case "testsub":
                if (Redis.isSubscribed("test")) {
                    Redis.unsubscribe("test");
                    sender.sendMessage("Unsubscribed from 'test' channel.");
                } else {
                    Redis.subscribe("test");
                    sender.sendMessage("Subscribed to 'test' channel. Incoming messages will log to console.");
                }
                return true;
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {

        if (!sender.hasPermission("minigames.manage")) return Collections.emptyList();

        List<String> topLevel = List.of("testsub", "testpub", "help");

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : topLevel) {
                if (s.startsWith(partial)) out.add(s);
            }
            return out;
        }

        return Collections.emptyList();
    }
}
