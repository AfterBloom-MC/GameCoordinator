package net.afterbloom.gameCoordinator;

import org.bukkit.Bukkit;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;


public class Redis {
    // Utility to split two concatenated JSON objects: returns index after first object's closing brace
    private static int findFirstJsonObjectEnd(String s) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        boolean started = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
                started = true;
            } else if (c == '}') {
                depth--;
                if (started && depth == 0) {
                    return i + 1; // position right after first JSON object
                }
            }
        }
        return -1; // not found
    }
    private static JedisPooled client;
    private static TrackingSubscriber defaultSubscriber;
    private static GameCoordinator plugin;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();


    public static Exception init(GameCoordinator mainPlugin) {
        plugin = mainPlugin;
        Utils.loadRedisDetails();
        try {
            URI uri = Utils.loadRedisDetails();
            if (uri != null) {
                client = new JedisPooled(uri);
                client.ping();
            }
            startHeartbeat();
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private static void startHeartbeat() {
        Logger logger = GameCoordinator.getLoggerInstance();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (client != null) {
                    client.ping();
                } else {
                    reconnect();
                }
            } catch (Exception e) {
                logger.severe("[Redis] Connection lost, attempting reconnect...");
                reconnect();
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private static void reconnect() {
        Logger logger = GameCoordinator.getLoggerInstance();
        logger.info("[Redis] Attempting reconnect...");
        try {
            URI uri = Utils.loadRedisDetails();
            if (uri != null) {
                client = new JedisPooled(uri);
                client.ping();
            }
            logger.info("[Redis] Reconnected successfully.");
            if (defaultSubscriber != null) {
                for (String channel : defaultSubscriber.activeChannels) {
                    subscribe(defaultSubscriber, channel);
                }
            }
        } catch (Exception e) {
            logger.severe("[Redis] Reconnect failed: " + e.getMessage());
        }
    }

    public static void publish(String channel, String message) {
        client.publish(channel, message);
    }

    private static void subscribe(JedisPubSub subscriber, String... channels) {
        new Thread(() -> client.subscribe(subscriber, channels)).start();
    }

    private static void unsubscribe(JedisPubSub subscriber, String... channels) {
        new Thread(() -> subscriber.unsubscribe(channels)).start();
    }

    public static void subscribe(String channel) {
        if (defaultSubscriber == null) {
            defaultSubscriber = new TrackingSubscriber();
        }
        if (!defaultSubscriber.isSubscribed(channel)) {
            subscribe(defaultSubscriber, channel);
        }
    }

    public static void unsubscribe(String channel) {
        if (defaultSubscriber != null && defaultSubscriber.isSubscribed(channel)) {
            unsubscribe(defaultSubscriber, channel);
        }
    }

    public static boolean isSubscribed(String channel) {
        return defaultSubscriber != null && defaultSubscriber.isSubscribed(channel);
    }

    public static class TrackingSubscriber extends JedisPubSub {
        private final Set<String> activeChannels = ConcurrentHashMap.newKeySet();

        @Override
        public void onSubscribe(String channel, int subscribedChannels) {
            activeChannels.add(channel);
        }

        @Override
        public void onUnsubscribe(String channel, int subscribedChannels) {
            activeChannels.remove(channel);
        }

        @Override
        public void onMessage(String channel, String message) {
            Logger logger = GameCoordinator.getLoggerInstance();

            String discoverChannel = plugin.getConfig().getString("discoverChannel");
            String gameChannel = plugin.getConfig().getString("gameChannel");
            String coordinatorId = "coord-" + plugin.getConfig().getString("serverId");
            try {
                // Parse only the first JSON object in the message to determine routing/function.
                String trimmed = message.trim();
                int firstEnd = findFirstJsonObjectEnd(trimmed);
                String firstJsonStr = (firstEnd > 0) ? trimmed.substring(0, firstEnd) : trimmed;
                JsonObject json = JsonParser.parseString(firstJsonStr).getAsJsonObject();
                String receiverId = json.has("receiverId") ? json.get("receiverId").getAsString() : (json.has("recieverId") ? json.get("recieverId").getAsString() : "");
                boolean addressedToUs = "*".equals(receiverId) || coordinatorId.equals(receiverId) || "coord-*".equals(receiverId);

                if (channel.equals(discoverChannel)) {
                    if (addressedToUs) {
                        String fn = json.has("function") ? json.get("function").getAsString() : "";
                        switch (fn) {
                            case "heartbeat":
                                // Only treat as a game server heartbeat if serverGame is present
                                if (json.has("serverGame")) {
                                    Servers.newServer(
                                            json.get("senderId").getAsString(),
                                            json.get("serverGame").getAsString()
                                    );
                                } else {
                                    // Coordinator heartbeat; no action needed here
                                }
                                break;
                            default:
                                // Unknown but addressed to us on discover
                                GameCoordinator.getLoggerInstance().info("[Redis] Unknown function on discover: " + fn);
                                break;
                        }
                    }
                } else if (channel.equals(gameChannel)) {
                    if (addressedToUs) {
                        String fn = json.has("function") ? json.get("function").getAsString() : "";
                        switch (fn) {
                            case "stats": {
                                try {
                                    // Support two-JSON concatenated message: first envelope, then stats map keyed by UUID
                                    String s = message.trim();
                                    int end = findFirstJsonObjectEnd(s);
                                    if (end > 0 && end < s.length()) {
                                        String first = s.substring(0, end).trim();
                                        String second = s.substring(end).trim();
                                        JsonObject env = JsonParser.parseString(first).getAsJsonObject();
                                        JsonObject statsMap = JsonParser.parseString(second).getAsJsonObject();
                                        String game = env.has("serverGame") ? env.get("serverGame").getAsString() : null;
                                        if (game != null) {
                                            StatsStorage.ingestStats(game, statsMap);
                                            GameCoordinator.getLoggerInstance().info("[Stats] Ingested stats for game=" + game + " players=" + statsMap.size());
                                        } else {
                                            GameCoordinator.getLoggerInstance().warning("[Stats] Missing serverGame in stats envelope from " + (env.has("senderId") ? env.get("senderId").getAsString() : "unknown"));
                                        }
                                    } else {
                                        GameCoordinator.getLoggerInstance().warning("[Stats] Could not split stats message into two JSON objects.");
                                    }
                                } catch (Exception ex) {
                                    GameCoordinator.getLoggerInstance().severe("[Stats] Failed to process stats: " + ex.getMessage());
                                }
                                break;
                            }
                            case "findServerResult": {
                                String player = json.get("player").getAsString();
                                boolean accepted = json.get("accepted").getAsBoolean();
                                if (accepted) {
                                    // Clear queue and notify player; actual transfer mechanism to be implemented
                                    Bukkit.getScheduler().runTask(Redis.plugin, () -> {
                                        var p = Redis.plugin.getServer().getPlayerExact(player);
                                        if (p != null) {
                                            p.sendMessage("[Minigames] Found a server! Joining...");
                                        }
                                    });
                                    Utils.clearPending(player);
                                } else {
                                    // Try next server in the player's queue
                                    String game = Utils.getPendingGame(player);
                                    if (game != null) {
                                        MinigameCommand.tryNextServer(player, game);
                                    } else {
                                        // No game tracked; just clear
                                        Utils.clearPending(player);
                                    }
                                }
                                break;
                            }
                            default:
                                // Unknown but addressed to us on game channel
                                GameCoordinator.getLoggerInstance().info("[Redis] Unknown function on gameChannel: " + fn);
                                break;
                        }
                    }
                }
            } catch (Exception e) {
                logger.severe("Failed to parse or handle json on channel " + channel + ": " + e.getMessage());
            }

            // Snoop messages for admins
            Bukkit.getScheduler().runTask(Redis.plugin, () -> {
                for (var player : Redis.plugin.getServer().getOnlinePlayers()) {
                    if (player.hasPermission("minigames.manage.snoop")) {
                        player.sendMessage("[Redis:" + channel + "] " + message);
                    }
                }
            });
        }

        public boolean isSubscribed(String channel) {
            return activeChannels.contains(channel);
        }
    }
}
