package net.afterbloom.gameCoordinator;

import org.bukkit.plugin.java.JavaPlugin;
import net.afterbloom.gameCoordinator.jedis.Jedis;
import java.util.logging.Logger;

/*
To do:
Everything
 */


public final class GameCoordinator extends JavaPlugin {

    private static Logger logger;

    @Override
    public void onEnable() {
        logger = getLogger();
        // Plugin startup logic
        logger.info("GameCoordinator is Booting!");

        logger.info("connecting to Redis server");
        String redisIp = "123.4.5.6";
        int redisPort = 1234;

        try (Jedis redis = new Jedis(redisIp, redisPort)) {
            redis.ping();
            logger.info("Connected to Redis");
        } catch (Exception e) {
            logger.severe("Failed to connect to Redis server! Is it running? Are the details correct in config.yml?");
            String error = e.getMessage();
            Utils.warnShutdown(error);
        }


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

    public static Logger getLoggerInstance() {
        return logger;
    }

}
