package net.afterbloom.gameCoordinator;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;
import java.net.URI;

public class Redis {
    private static JedisPooled client;

    // Initialize the Redis connection
    public static Exception init() {

        Utils.loadRedisDetails();

        try {
            URI uri = Utils.loadRedisDetails();
            if (uri != null) {
                client = new JedisPooled(uri);
                client.ping();
                return null;
            } else {
                return null;
            }
        } catch (Exception e) {
            return e;
        }
    }

    // Publish a message to a channel
    public static void publish(String channel, String message) {
        client.publish(channel, message);
    }

    // Subscribe to one or more channels
    public static void subscribe(JedisPubSub subscriber, String... channels) {
        // subscription is blocking, so usually run in its own thread
        new Thread(() -> client.subscribe(subscriber, channels)).start();
    }
}