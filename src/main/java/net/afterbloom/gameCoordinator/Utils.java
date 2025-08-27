package net.afterbloom.gameCoordinator;
import java.util.logging.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import static java.net.URLEncoder.encode;


public class Utils {

    private static final HttpClient client = HttpClient.newHttpClient();
    private static JavaPlugin plugin;
    private static boolean hasShutdown = false;

    public static void init(JavaPlugin mainPlugin) {
        plugin = mainPlugin;
    }

    public static void shutdown(String error) {
        if (hasShutdown) return;  // <--- prevents double execution
        hasShutdown = true;
        Logger logger = GameCoordinator.getLoggerInstance();
        Bukkit.getPluginManager().disablePlugin(plugin);
        for (int i = 0; i < 10; i++) {
            logger.severe("---------------------Game Coordinator has shut down---------------------");
            logger.severe(error);
            logger.severe("Minigames will **not** function without this plugin, Investigate immediatley");
        }
        //my justification for looping this 10 times:
        //in our case, this is an **essential** plugin. I want to know it has shut down.. immediately

        //send webhook to warn of plugin failure
        String password = plugin.getConfig().getString("webhookMessage");
        String port = plugin.getConfig().getString("webhookUrl");

        String message = plugin.getConfig().getString("webhookMessage");
        String webhookUrl = plugin.getConfig().getString("webhookUrl");

        String jsonPayload = "{\"content\":\"" + message + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding());

    }

    public static URI loadRedisDetails() {
        Logger logger = GameCoordinator.getLoggerInstance();
        String host = plugin.getConfig().getString("redisHost");
        String password = plugin.getConfig().getString("redisPassword");
        String port = plugin.getConfig().getString("redisPort");

        try {
            if (isValidString(password) && isValidString(host) && isValidString(port)) {
                URI uri = new URI("redis://:" + encode(password, "UTF-8") + "@" + host + ":" + port);
                return uri;
            } else {
                logger.severe("Failed to load Redis details from config.yml! Are they all strings and not null?");
                Utils.shutdown("Failed to load Redis details from config.yml");
                return null;
            }
        } catch (Exception e) {
            logger.severe("Failed to create Redis connection string from details in config.yml, error: " + e.toString());
            Utils.shutdown(e.getMessage());
            return null;
        }
    }

    private static boolean isValidString(Object obj) {
        return obj instanceof String && !((String) obj).isEmpty();
    }
}
