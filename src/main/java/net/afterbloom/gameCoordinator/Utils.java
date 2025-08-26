package net.afterbloom.gameCoordinator;
import java.util.logging.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class Utils {

    private static final HttpClient client = HttpClient.newHttpClient();

    public static void warnShutdown(String error) {
        Logger logger = GameCoordinator.getLoggerInstance();
        for (int i = 0; i < 10; i++) {
            logger.severe("---------------------Game Coordinator has shut down---------------------");
            logger.severe(error);
            logger.severe("Minigames will **not** function witouth this plugin, Investigate immediatley");
        }
        //my justification for looping this 10 times:
        //in our case, this is an **essential** plugin. I want to know it has shut down.. immediately

        //send webhook to warn of plugin failure

        String message = "<@USERID> GameCoordinator has shut down!";
        String webhookUrl = "https://discord.com/api/webhooks/1377290209280131112/jx3R3QVkSSXxRs7cu2bT1NxlhvXWiL-i8i6Q7uRbfqKMbWjIxWbYUm7Wf754FV6uHWQf";

        String jsonPayload = "{\"content\":\"" + message + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.discarding());

    }
}
