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
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;


public class Redis {
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
            String coordinatorId = "coord-" + plugin.getConfig().getString("serverId");
            if (channel.equals(discoverChannel)) {
                try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();

                    List<String> respondsTo = new ArrayList<>();
                    respondsTo.add("*");
                    respondsTo.add(coordinatorId);
                    respondsTo.add("coord-*");

                    String recieverId = json.get("recieverId").getAsString();

                    if (respondsTo.contains(recieverId)){
                        switch (json.get("function").getAsString()) {

                            case "announceRunningServer":
                                String gameServerId = json.get("senderId").getAsString();
                                String gameId = json.get("serverGame").getAsString();
                                Servers.newServer(gameServerId, gameId);


                            default:
                                logger.info("Unknown function recieved.");
                        }
                    }
                } catch (Exception e) {
                    logger.severe("Failed to parse json on channel " + channel);
                    logger.severe("Error: " + e.getMessage());
                }

                /*try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(message).getAsJsonObject();
                    String recieveId = json.get("recieverId").getAsString();
                    if ("heartbeat".equals(recieveId)) {
                        String serverId = json.get("senderId").getAsString();
                        String serverGame = json.get("serverGame").getAsString();
                        Servers.newServer(serverId, serverGame);
                    }
                } catch (Exception e) {
                   logger.severe("Failed to parse server's reply: " + message);
                }*/
            }



            Bukkit.getScheduler().runTask(Redis.plugin, () -> {
                for (var player : Redis.plugin.getServer().getOnlinePlayers()) {
                    if (player.hasPermission("minigames.manage")) {
                        player.sendMessage("[Redis:" + channel + "] " + message);
                    }
                    logger.info("Recived unknown message on channel: " + channel + "\n" + message);
                }
            });
        }

        public boolean isSubscribed(String channel) {
            return activeChannels.contains(channel);
        }
    }
}
