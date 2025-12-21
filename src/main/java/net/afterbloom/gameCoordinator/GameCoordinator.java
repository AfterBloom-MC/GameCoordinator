package net.afterbloom.gameCoordinator;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;

import java.util.logging.Logger;

/*
To do:
Everything else
store servers that respond to initial ping
 */


public final class GameCoordinator extends JavaPlugin {

    private static Logger logger;

    @Override
    public void onEnable() {
        Utils.init(this);
        saveDefaultConfig();
        logger = getLogger();
        // Plugin startup logic
        GameCoordinator.getLoggerInstance().info("GameCoordinator is Booting!");

        CoordinatorCommand coordinator = new CoordinatorCommand(this);
        this.getCommand("coordinator").setExecutor(coordinator);
        this.getCommand("coordinator").setTabCompleter(coordinator);

        MinigameCommand minigame = new MinigameCommand(this);
        this.getCommand("minigame").setExecutor(minigame);
        this.getCommand("minigame").setTabCompleter(minigame);

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
                String heartbeatJson = "{\"receiverId\":\"*\",\"senderId\":\"" + coordinatorSenderId + "\",\"function\":\"heartbeat\"}";
                Redis.publish(discoverChannel, heartbeatJson);
            } catch (Exception e) {
                GameCoordinator.getLoggerInstance().severe("Failed to publish coordinator heartbeat: " + e.getMessage());
            }
        }, 20L * 10, 20L * 10);

        // Schedule periodic prune of stale servers (every 60s, prune entries older than 90s)
        Bukkit.getScheduler().runTaskTimer(this, () -> Servers.prune(90), 20L * 60, 20L * 60);

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

}
