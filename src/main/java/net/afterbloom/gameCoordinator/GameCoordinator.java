package net.afterbloom.gameCoordinator;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;
import com.google.gson.JsonObject;

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
        logger.info("GameCoordinator is Booting!");

        CoordinatorCommand coordinator = new CoordinatorCommand(this);
        this.getCommand("coordinator").setExecutor(coordinator);
        this.getCommand("coordinator").setTabCompleter(coordinator);

        logger.info("connecting to Redis server");
        Exception initResult = Redis.init(this);
        if (initResult == null) {
            logger.info("succesfully connected to Redis");
        } else {
            logger.severe("Failed to connect to redis with error: " + initResult.getMessage());
            Utils.shutdown(initResult.toString());
            return;
        }


        logger.info("Attempting to contact minigames servers");

        String discoverChannel = getConfig().getString("discoverChannel");

        try {
            Redis.subscribe(discoverChannel);
            logger.info("Succesfully subscribed to discoverChannel");
        } catch (Exception e) {
            logger.severe("Failed to subscribe to "+ discoverChannel + " Error:  " + e.getMessage());
            Utils.shutdown(e.toString());
        }

        JsonObject message = new JsonObject();
        message.addProperty("senderId", "coord-01");
        message.addProperty("recieverId", "game-*");
        message.addProperty("function", "announceCoordinatorStart");

        try {
            Redis.publish("CoordinatorAnnounce", message.toString());
            logger.info("Succesfully sent Announcement Ping");
        } catch (Exception e) {
            logger.severe("Failed to announce gameCoordinator" + e.getMessage());
            Utils.shutdown(e.toString());
        }
        // Send out ping and Asynchronously await response
        //logger.info("Listening for new servers....");
        // Set up Asynchronous listener for plugin messages
        // teddan is stinky

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        logger.info("GameCoordinator is Shutting down!");

        //logger.info("Contacting minigames servers");
        //tell minigames server to go dormant & clean up (leave events mode if in it)

        //logger.info("Saving Data");
        //save all leaderboards, stats etc



        logger.info("GameCoordinator has finished shutting down!");
    }

    public static Logger getLoggerInstance() {
        return logger;
    }

}
