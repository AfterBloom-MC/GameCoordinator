package net.afterbloom.gameCoordinator;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/*
To do:
Everything else
store servers that respond to initial ping
 */


public final class GameCoordinator extends JavaPlugin {

    private static Logger logger;
    private SupporterConfig supporterConfig;

    @Override
    public void onEnable() {
        Utils.init(this);
        VanishManager.init(this);
        saveDefaultConfig();
        supporterConfig = new SupporterConfig(this);
        logger = getLogger();
        // Plugin startup logic
        GameCoordinator.getLoggerInstance().info("GameCoordinator is Booting!");

        boolean supporterMode = getConfig().getBoolean("supporterMode", false);
        if (supporterMode) {
            GameCoordinator.getLoggerInstance().info("Running in SUPPORTER mode.");
        }

        if (!supporterMode) {
            CoordinatorCommand coordinator = new CoordinatorCommand(this);
            this.getCommand("coordinator").setExecutor(coordinator);
            this.getCommand("coordinator").setTabCompleter(coordinator);

            MinigameCommand minigame = new MinigameCommand(this);
            this.getCommand("minigame").setExecutor(minigame);
            this.getCommand("minigame").setTabCompleter(minigame);

            StaffCommands staffCommands = new StaffCommands(this);
            getCommand("report").setExecutor(staffCommands);
            getCommand("report").setTabCompleter(staffCommands);
            getCommand("follow-player").setExecutor(staffCommands);
            getCommand("follow-player").setTabCompleter(staffCommands);
            getCommand("find").setExecutor(staffCommands);
            getCommand("find").setTabCompleter(staffCommands);
            getCommand("mgtp").setExecutor(staffCommands);
            getCommand("mgtp").setTabCompleter(staffCommands);
            getCommand("staffmode").setExecutor(staffCommands);

            Bukkit.getPluginManager().registerEvents(new StaffEvents(), this);
        }

        // Initialize Stats storage (MySQL/MariaDB)
        try {
            StatsStorage.init(this);
            GameCoordinator.getLoggerInstance().info("Stats storage initialized.");
        } catch (Exception e) {
            GameCoordinator.getLoggerInstance().severe("Failed to initialize Stats storage: " + e.getMessage());
            Utils.shutdown(e.toString());
            return;
        }

        GameCoordinator.getLoggerInstance().info("connecting to Redis server");
        Exception initResult = Redis.init(this);
        if (initResult == null) {
            GameCoordinator.getLoggerInstance().info("succesfully connected to Redis");
        } else {
            GameCoordinator.getLoggerInstance().severe("Failed to connect to redis with error: " + initResult.getMessage());
            Utils.shutdown(initResult.toString());
            return;
        }


        if (!supporterMode) {
            GameCoordinator.getLoggerInstance().info("Attempting to contact minigames servers");

            String discoverChannel = getConfig().getString("discoverChannel");
            String gameChannel = getConfig().getString("gameChannel");

            try {
                Redis.subscribe(discoverChannel);
                Redis.subscribe(gameChannel);
                GameCoordinator.getLoggerInstance().info("Successfully subscribed to channels: discover=" + discoverChannel + ", game=" + gameChannel);
            } catch (Exception e) {
                GameCoordinator.getLoggerInstance().severe("Failed to subscribe to channels. Error:  " + e.getMessage());
                Utils.shutdown(e.toString());
            }

            String coordinatorSenderIdNow = "coord-" + getConfig().getString("serverId");
            String announceJson = "{\"receiverId\":\"game-*\",\"senderId\":\"" + coordinatorSenderIdNow + "\",\"function\":\"announceCoordinatorStart\"}";

            try {
                Redis.publish(discoverChannel, announceJson);
                GameCoordinator.getLoggerInstance().info("Successfully sent Announcement Ping on discover channel");
            } catch (Exception e) {
                GameCoordinator.getLoggerInstance().severe("Failed to announce GameCoordinator: " + e.getMessage());
                Utils.shutdown(e.toString());
            }

            // Schedule periodic coordinator heartbeat every 10 seconds on discoverChannel
            final String coordinatorSenderId = "coord-" + getConfig().getString("serverId");
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    JsonObject heartbeat = new JsonObject();
                    heartbeat.addProperty("receiverId", "*");
                    heartbeat.addProperty("senderId", coordinatorSenderId);
                    heartbeat.addProperty("function", "heartbeat");
                    heartbeat.addProperty("playerCount", Bukkit.getOnlinePlayers().size());

                    com.google.gson.JsonArray players = new com.google.gson.JsonArray();
                    for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                        players.add(p.getName());
                    }
                    heartbeat.add("players", players);

                    // If it's acting as a game server, we might want to include lobbyCount as well
                    String localGame = getConfig().getString("serverGame");
                    if (localGame != null) {
                        heartbeat.addProperty("serverGame", localGame);
                        heartbeat.addProperty("lobbyCount", 1);
                    }

                    Redis.updateServerState(coordinatorSenderId, heartbeat);
                    Redis.publish(discoverChannel, heartbeat.toString());
                } catch (Exception e) {
                    GameCoordinator.getLoggerInstance().severe("Failed to publish coordinator heartbeat: " + e.getMessage());
                }
            }, 20L * 10, 20L * 10);

            // Periodic aggregation task (every 1 second for max 2s delay)
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                long cutoff = (System.currentTimeMillis() / 1000) - 90;
                Object result = Redis.aggregate(cutoff);
                if (result instanceof List<?> list && list.size() == 2) {
                    int total = ((Long) list.get(0)).intValue();
                    String gamesJson = (String) list.get(1);
                    JsonObject gamesObj = JsonParser.parseString(gamesJson).getAsJsonObject();
                    Map<String, Servers.GameStats> statsMap = new HashMap<>();
                    for (String game : gamesObj.keySet()) {
                        JsonObject g = gamesObj.getAsJsonObject(game);
                        statsMap.put(game.toLowerCase(), new Servers.GameStats(
                                g.get("p").getAsInt(),
                                g.get("s").getAsInt(),
                                g.get("l").getAsInt()
                        ));
                    }
                    Servers.updateGlobalStats(total, statsMap);
                }
            }, 20L, 20L);

            // Schedule periodic prune of stale servers (every 60s, prune entries older than 90s)
            Bukkit.getScheduler().runTaskTimer(this, () -> Servers.prune(90), 20L * 60, 20L * 60);
        } else {
            // Supporter mode still needs to listen for server heartbeats and stats to supply placeholders
            String discoverChannel = getConfig().getString("discoverChannel");
            String gameChannel = getConfig().getString("gameChannel");

            try {
                Redis.subscribe(discoverChannel);
                Redis.subscribe(gameChannel);
                GameCoordinator.getLoggerInstance().info("Supporter mode: Subscribed to channels for placeholders.");
            } catch (Exception e) {
                GameCoordinator.getLoggerInstance().severe("Supporter mode: Failed to subscribe to channels. Error: " + e.getMessage());
            }

            // Periodic aggregation task for Supporter mode too (to keep placeholders fresh)
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                long cutoff = (System.currentTimeMillis() / 1000) - 90;
                Object result = Redis.aggregate(cutoff);
                if (result instanceof List<?> list && list.size() == 2) {
                    int total = ((Long) list.get(0)).intValue();
                    String gamesJson = (String) list.get(1);
                    JsonObject gamesObj = JsonParser.parseString(gamesJson).getAsJsonObject();
                    Map<String, Servers.GameStats> statsMap = new HashMap<>();
                    for (String game : gamesObj.keySet()) {
                        JsonObject g = gamesObj.getAsJsonObject(game);
                        statsMap.put(game.toLowerCase(), new Servers.GameStats(
                                g.get("p").getAsInt(),
                                g.get("s").getAsInt(),
                                g.get("l").getAsInt()
                        ));
                    }
                    Servers.updateGlobalStats(total, statsMap);
                }
            }, 20L, 20L);

            // Still need to prune stale servers so placeholders don't show old data
            Bukkit.getScheduler().runTaskTimer(this, () -> Servers.prune(90), 20L * 60, 20L * 60);
        }

        // Register PlaceholderAPI expansion if available
        Bukkit.getScheduler().runTask(this, () -> {
            Utils.debugLog("Checking for PlaceholderAPI (post-startup)...");
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                Utils.debugLog("PlaceholderAPI found! Registering expansion...");
                try {
                    boolean registered = new GameCoordinatorPlaceholders(this).register();
                    if (registered) {
                        GameCoordinator.getLoggerInstance().info("PlaceholderAPI expansion registered successfully.");
                    } else {
                        GameCoordinator.getLoggerInstance().warning("PlaceholderAPI expansion failed to register.");
                    }
                } catch (Exception e) {
                    GameCoordinator.getLoggerInstance().severe("Error while registering PlaceholderAPI expansion: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Utils.debugLog("PlaceholderAPI not found on this server.");
            }
        });

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        GameCoordinator.getLoggerInstance().info("GameCoordinator is Shutting down!");

        // Flush/close stats storage
        try {
            StatsStorage.shutdown();
        } catch (Exception ignored) {}

        //logger.info("Contacting minigames servers");
        //tell minigames server to go dormant & clean up (leave events mode if in it)

        //logger.info("Saving Data");
        //save all leaderboards, stats etc

        GameCoordinator.getLoggerInstance().info("GameCoordinator has finished shutting down!");
    }

    public static Logger getLoggerInstance() {
        return logger;
    }

    public SupporterConfig getSupporterConfig() {
        return supporterConfig;
    }

}
