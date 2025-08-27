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

public class Redis {
    private static JedisPooled client;

    private static TrackingSubscriber defaultSubscriber;

    private static GameCoordinator plugin;

    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Initialize Redis
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
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (client != null) {
                    client.ping();
                } else {
                    reconnect();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Redis] Connection lost, attempting reconnect...");
                reconnect();
            }
        }, 20, 20, TimeUnit.SECONDS);
    }

    private static void reconnect() {
        plugin.getLogger().info("[Redis] Attempting reconnect...");

        try {
            URI uri = Utils.loadRedisDetails();
            if (uri != null) {
                client = new JedisPooled(uri);
                client.ping();
            }

            plugin.getLogger().info("[Redis] Reconnected successfully.");

            // Resubscribe default subscriber
            if (defaultSubscriber != null) {
                for (String channel : defaultSubscriber.activeChannels) {
                    subscribe(defaultSubscriber, channel);
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("[Redis] Reconnect failed: " + e.getMessage());
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
            Bukkit.getScheduler().runTask(Redis.plugin, () -> {
                for (var player : Redis.plugin.getServer().getOnlinePlayers()) {
                    if (player.hasPermission("minigames.manage")) {
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
