package net.afterbloom.gameCoordinator;

import org.bukkit.Bukkit;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisPubSub;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import redis.clients.jedis.params.ZAddParams;


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

    private static final String KEY_SERVERS = "gc:servers";
    private static final String KEY_ACTIVE_SERVERS = "gc:active_servers";
    private static final String LUA_AGGREGATE =
            "local cutoff = ARGV[1] " +
            "redis.call('ZREMRANGEBYSCORE', '" + KEY_ACTIVE_SERVERS + "', '-inf', cutoff) " +
            "local active_ids = redis.call('ZRANGE', '" + KEY_ACTIVE_SERVERS + "', 0, -1) " +
            "local total = 0 " +
            "local games = {} " +
            "for _, id in ipairs(active_ids) do " +
            "  local data = redis.call('HGET', '" + KEY_SERVERS + "', id) " +
            "  if data then " +
            "    local server = cjson.decode(data) " +
            "    if not id:find('^supporter-') then " +
            "      total = total + server.playerCount " +
            "    end " +
            "    if server.serverGame then " +
            "      local g = server.serverGame " +
            "      if not games[g] then games[g] = {p=0, s=0, l=0} end " +
            "      games[g].s = games[g].s + 1 " +
            "      games[g].p = games[g].p + server.playerCount " +
            "      games[g].l = games[g].l + (server.lobbyCount or 0) " +
            "    end " +
            "  end " +
            "end " +
            "return {total, cjson.encode(games)}";
    private static String luaSha;

    public static Exception init(GameCoordinator mainPlugin) {
        plugin = mainPlugin;
        Utils.loadRedisDetails();
        try {
            URI uri = Utils.loadRedisDetails();
            if (uri != null) {
                client = new JedisPooled(uri);
                client.ping();
                luaSha = client.scriptLoad(LUA_AGGREGATE);
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

    public static void updateServerState(String serverId, JsonObject state) {
        if (client == null) return;
        try {
            client.hset(KEY_SERVERS, serverId, state.toString());
            client.zadd(KEY_ACTIVE_SERVERS, System.currentTimeMillis() / 1000.0, serverId);
        } catch (Exception e) {
            GameCoordinator.getLoggerInstance().severe("[Redis] Failed to update server state: " + e.getMessage());
        }
    }

    public static Object aggregate(long staleCutoff) {
        if (client == null || luaSha == null) return null;
        try {
            return client.evalsha(luaSha, Collections.emptyList(), Collections.singletonList(String.valueOf(staleCutoff)));
        } catch (Exception e) {
            // If script is lost, reload and retry
            try {
                luaSha = client.scriptLoad(LUA_AGGREGATE);
                return client.evalsha(luaSha, Collections.emptyList(), Collections.singletonList(String.valueOf(staleCutoff)));
            } catch (Exception ex) {
                GameCoordinator.getLoggerInstance().severe("[Redis] Aggregation failed: " + ex.getMessage());
                return null;
            }
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
            String prefix = plugin.getConfig().getBoolean("supporterMode", false) ? "supporter-" : "coord-";
            String coordinatorId = prefix + plugin.getConfig().getString("serverId");
            try {
                // Parse only the first JSON object in the message to determine routing/function.
                String trimmed = message.trim();
                Utils.debugLog("[Redis] Received raw message on " + channel + ": " + trimmed);
                int firstEnd = findFirstJsonObjectEnd(trimmed);
                String firstJsonStr = (firstEnd > 0) ? trimmed.substring(0, firstEnd) : trimmed;
                JsonObject json = JsonParser.parseString(firstJsonStr).getAsJsonObject();
                Utils.debugLog("[Redis] [DEBUG_DIAGNOSTIC] Parsed JSON: " + json.toString());
                String receiverId = "";
                if (json.has("receiverId")) {
                    receiverId = json.get("receiverId").getAsString();
                } else if (json.has("recieverId")) {
                    receiverId = json.get("recieverId").getAsString();
                    Utils.debugLog("[Redis] Using legacy 'recieverId' field: " + receiverId);
                } else {
                    Utils.debugLog("[Redis] No receiverId/recieverId found in message");
                }
                
                // Address matching logic:
                // 1. "*" matches everyone.
                // 2. Exact match (coordinatorId).
                // 3. "coord-*" matches any coordinator (senderId does NOT start with supporter-).
                // 4. "supporter-*" matches any supporter (senderId starts with supporter-).
                boolean isSupporter = plugin.getConfig().getBoolean("supporterMode", false);
                boolean addressedToUs = "*".equals(receiverId) || coordinatorId.equals(receiverId);
                // Utils.debugLog("[Redis] receiverId=" + receiverId + " coordinatorId=" + coordinatorId + " isSupporter=" + isSupporter + " addressedToUs=" + addressedToUs);
                if (!addressedToUs) {
                    if ("coord-*".equals(receiverId) && !isSupporter) {
                        addressedToUs = true;
                        // Utils.debugLog("[Redis] Matched coord-* as non-supporter");
                    } else if ("supporter-*".equals(receiverId) && isSupporter) {
                        addressedToUs = true;
                        // Utils.debugLog("[Redis] Matched supporter-* as supporter");
                    }
                }

                if (channel.equals(discoverChannel)) {
                    String fn = json.has("function") ? json.get("function").getAsString() : "";
                    Utils.debugLog("[Redis] [DEBUG_DIAGNOSTIC] Function=" + fn + " isSupporter=" + plugin.getConfig().getBoolean("supporterMode", false) + " discoverChannel='" + discoverChannel + "' channel='" + channel + "'");
                    
                    // Always respond to requestTotalPlayers if we are a coordinator, 
                    // regardless of addressedToUs, to ensure any server can get the total.
                    if ("requestTotalPlayers".equals(fn) && !plugin.getConfig().getBoolean("supporterMode", false)) {
                        String reqSenderId = json.has("senderId") ? json.get("senderId").getAsString() : "unknown";
                        long requestId = json.has("requestId") ? json.get("requestId").getAsLong() : 0;
                        
                        // Calculate total players
                        int localPlayerCount = Bukkit.getOnlinePlayers().size();
                        int remotePlayers = Servers.getServers().entrySet().stream()
                                .filter(e -> !e.getKey().startsWith("supporter-"))
                                .mapToInt(e -> e.getValue().getPlayerCount())
                                .sum();
                        int total = remotePlayers + localPlayerCount;
                        Utils.debugLog("[Redis] Responding to requestTotalPlayers from " + reqSenderId + ": local=" + localPlayerCount + " remote=" + remotePlayers + " total=" + total);
                        Utils.debugLog("[Redis] [DEBUG_DIAGNOSTIC] Sending response on channel: " + discoverChannel);

                        JsonObject response = new JsonObject();
                        response.addProperty("receiverId", reqSenderId);
                        response.addProperty("senderId", coordinatorId);
                        response.addProperty("function", "totalPlayersResponse");
                        response.addProperty("totalPlayers", total);
                        response.addProperty("requestId", requestId);
                        try {
                            Utils.debugLog("[Redis] [DEBUG_DIAGNOSTIC] Publishing message: " + response.toString());
                            Redis.publish(discoverChannel, response.toString());
                            Utils.debugLog("[Redis] [DEBUG_DIAGNOSTIC] Successfully published response");
                        } catch (Exception e) {
                            GameCoordinator.getLoggerInstance().severe("[Redis] Failed to publish totalPlayersResponse: " + e.getMessage());
                        }
                    }

                    // Utils.debugLog("[Redis] Function=" + fn + " addressedToUs=" + addressedToUs);
                    // Heartbeats are global state updates; process them even if specifically addressed to another coordinator
                    // to ensure all nodes (including supporters) have an accurate view of the network.
                    if (addressedToUs || "heartbeat".equals(fn) || "totalPlayersResponse".equals(fn) || "requestTotalPlayers".equals(fn)) {
                        // Utils.debugLog("[Redis] Processing function=" + fn + " (addressedToUs=" + addressedToUs + ")");
                        switch (fn) {
                            case "requestTotalPlayers":
                                // Handled generically above
                                break;
                            case "totalPlayersResponse": {
                                // Utils.debugLog("[Redis] Handling totalPlayersResponse addressedToUs=" + addressedToUs);
                                if (plugin.getConfig().getBoolean("supporterMode", false)) {
                                    int total = json.has("totalPlayers") ? json.get("totalPlayers").getAsInt() : 0;
                                    long requestId = json.has("requestId") ? json.get("requestId").getAsLong() : 0;
                                    // Utils.debugLog("[Redis] Updating network total to " + total + " from request " + requestId);
                                    Servers.setNetworkTotalPlayers(total, requestId);
                                }
                                break;
                            }
                            case "heartbeat":
                                String heartbeatSenderId = json.has("senderId") ? json.get("senderId").getAsString() : "unknown";
                                if (heartbeatSenderId.equals(coordinatorId)) break; // Ignore our own heartbeat

                                if (json.has("serverGame")) {
                                    int pCount = json.has("playerCount") ? json.get("playerCount").getAsInt() : 0;
                                    int lCount = json.has("lobbyCount") ? json.get("lobbyCount").getAsInt() : 0;
                                    java.util.List<String> playerList = new java.util.ArrayList<>();
                                    if (json.has("players") && json.get("players").isJsonArray()) {
                                        JsonArray array = json.getAsJsonArray("players");
                                        for (int i = 0; i < array.size(); i++) {
                                            playerList.add(array.get(i).getAsString());
                                        }
                                    }
                                    Utils.debugLog("[Redis] Heartbeat from game server " + heartbeatSenderId + ": players=" + pCount + " lobbies=" + lCount + " players=" + playerList);
                                    Servers.newServer(
                                            heartbeatSenderId,
                                            json.get("serverGame").getAsString(),
                                            pCount,
                                            lCount,
                                            playerList
                                    );
                                    // Update Redis state with this heartbeat info to keep it fresh in the registry
                                    updateServerState(heartbeatSenderId, json);
                                } else if (heartbeatSenderId.startsWith("coord-") || heartbeatSenderId.startsWith("supporter-")) {
                                    // Other coordinator/supporter heartbeat; register it as a coordinator server
                                    int pCount = json.has("playerCount") ? json.get("playerCount").getAsInt() : 0;
                                    int lCount = json.has("lobbyCount") ? json.get("lobbyCount").getAsInt() : 0;
                                    java.util.List<String> playerList = new java.util.ArrayList<>();
                                    if (json.has("players") && json.get("players").isJsonArray()) {
                                        JsonArray array = json.getAsJsonArray("players");
                                        for (int i = 0; i < array.size(); i++) {
                                            playerList.add(array.get(i).getAsString());
                                        }
                                    }
                                    Utils.debugLog("[Redis] Heartbeat from " + (heartbeatSenderId.startsWith("coord-") ? "coordinator " : "supporter ") + heartbeatSenderId + ": players=" + pCount + " lobbies=" + lCount + " players=" + playerList);
                                    Servers.newServer(
                                            heartbeatSenderId,
                                            json.has("serverGame") ? json.get("serverGame").getAsString() : "coordinator",
                                            pCount,
                                            lCount,
                                            playerList
                                    );
                                    // Update Redis state with this heartbeat info to keep it fresh in the registry
                                    updateServerState(heartbeatSenderId, json);
                                }
                                break;
                            case "reportAlert": {
                                String reporter = json.has("reporter") ? json.get("reporter").getAsString() : "Unknown";
                                String target = json.has("target") ? json.get("target").getAsString() : "Unknown";
                                String reason = json.has("reason") ? json.get("reason").getAsString() : "No reason";
                                String server = json.has("server") ? json.get("server").getAsString() : "Unknown";
                                
                                // Coordinator sends to Discord
                                if (!plugin.getConfig().getBoolean("supporterMode", false)) {
                                    String discordMessage = "**[REPORT]** " + reporter + " reported " + target + " on " + server + " for: " + reason;
                                    Utils.sendReportWebhook(discordMessage);
                                }

                                String alert = "§8[§c§lREPORT§8] §f" + reporter + " §7reported §f" + target + " §7on §f" + server + " §7for: §f" + reason;
                                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                                    if (p.hasPermission("minigames.staff")) {
                                        p.sendMessage(alert);
                                    }
                                }
                                break;
                            }
                            case "teleportRequest": {
                                if (json.has("targetPlayer")) {
                                    String targetPlayer = json.get("targetPlayer").getAsString();
                                    String staffPlayer = json.get("staffPlayer").getAsString();
                                    org.bukkit.entity.Player staff = Bukkit.getPlayer(staffPlayer);
                                    if (staff != null) {
                                        org.bukkit.entity.Player target = Bukkit.getPlayer(targetPlayer);
                                        if (target != null) {
                                            staff.teleport(target);
                                            VanishManager.vanish(staff);
                                            staff.sendMessage("§aTeleported to " + targetPlayer + " in vanish.");
                                        }
                                    }
                                }
                                break;
                            }
                            default:
                                // Unknown but addressed to us on discover
                                GameCoordinator.getLoggerInstance().info("[Redis] Unknown function on discover: " + fn);
                                break;
                        }
                    }
                } else if (channel.equals(gameChannel)) {
                    if (addressedToUs) {
                        String fn = json.has("function") ? json.get("function").getAsString() : "";
                        Utils.debugLog("[Redis] Received game message: function=" + fn + " from=" + (json.has("senderId") ? json.get("senderId").getAsString() : "unknown"));
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
                                        GameCoordinator.getLoggerInstance().warning("[Stats] Could not split stats message into two JSON objects. Dumping message:" + s);
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
