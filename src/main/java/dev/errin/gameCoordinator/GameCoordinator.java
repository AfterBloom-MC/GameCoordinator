package dev.errin.gameCoordinator;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Logger;

/*
To do:
Everything
 */


public final class GameCoordinator extends JavaPlugin {

    private Logger logger;

    @Override
    public void onEnable() {
        String DiscoverChannel = "MinigameDiscoverChannel";
        String AsignChannel = "MingameAssignChannel";

        logger = getLogger();
        // Plugin startup logic
        logger.info("GameCoordinator is Booting!");

        logger.info("Attempting to contact minigames servers");
        // Send out ping and Asynchronously await response

        logger.info("Listening for new servers....");
        // Set up Asynchronous listener for plugin messages

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        logger.info("GameCoordinator is Shutting down!");

        logger.info("Contacting minigames servers");
        //tell minigames server to go dormant & clean up (leave events mode if in it)

        logger.info("Saving Data");
        //save all leaderboards, stats etc

        logger.info("Resetting to default state");
        //reset all persistent data for next time, eg events mode state

        logger.info("GameCoordinator has finished shutting down!");
    }
}
