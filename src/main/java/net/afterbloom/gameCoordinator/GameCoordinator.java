package net.afterbloom.gameCoordinator;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/*
To do:
Everything
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
        Exception e = Redis.init(this);
        if (e == null) {
            logger.info("succesfully connected to Redis");
        } else {
            logger.severe("Failed to connect to redis with error: " + e.toString());
            Utils.shutdown(e.getMessage());
        }


        //logger.info("Attempting to contact minigames servers");
        // Send out ping and Asynchronously await response
        //logger.info("Listening for new servers....");
        // Set up Asynchronous listener for plugin messages

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
