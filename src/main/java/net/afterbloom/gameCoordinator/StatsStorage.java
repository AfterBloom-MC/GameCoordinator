package net.afterbloom.gameCoordinator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stats storage with per-game, columnar tables and time slices (total/weekly/monthly/yearly).
 * - Dynamic columns: new stat keys result in ALTER TABLE ADD COLUMN (DOUBLE NOT NULL DEFAULT 0)
 * - Ingestion: incoming values are treated as deltas and added to existing totals
 * - Resets: weekly (Mon 00:00), monthly (1st 00:00), yearly (Jan 1 00:00) with JSON backup prior to TRUNCATE
 */
public final class StatsStorage {
    private static HikariDataSource dataSource;
    private static org.bukkit.plugin.java.JavaPlugin plugin;
    private static final Set<String> knownGames = ConcurrentHashMap.newKeySet();
    private static final Map<String, Object> schemaLocks = new ConcurrentHashMap<>();

    // slices we maintain
    public enum Slice { TOTAL, WEEKLY, MONTHLY, YEARLY }

    public static void init(org.bukkit.plugin.java.JavaPlugin mainPlugin) throws Exception {
        plugin = mainPlugin;
        Logger logger = GameCoordinator.getLoggerInstance();
        String host = plugin.getConfig().getString("mysql.host");
        String port = plugin.getConfig().getString("mysql.port");
        String database = plugin.getConfig().getString("mysql.database");
        String user = plugin.getConfig().getString("mysql.user");
        String password = plugin.getConfig().getString("mysql.password");
        boolean useSSL = plugin.getConfig().getBoolean("mysql.useSSL", false);
        boolean allowPublicKeyRetrieval = plugin.getConfig().getBoolean("mysql.allowPublicKeyRetrieval", true);

        if (!isNonEmpty(host) || !isNonEmpty(port) || !isNonEmpty(database) || !isNonEmpty(user)) {
            throw new IllegalStateException("Missing MySQL configuration. Please set mysql.{host,port,database,user,password} in config.yml");
        }

        HikariConfig cfg = new HikariConfig();
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useUnicode=true&characterEncoding=utf8&useSSL=" + useSSL +
                "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval +
                "&serverTimezone=UTC";
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(5);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("GameCoordinator-StatsPool");

        dataSource = new HikariDataSource(cfg);
        // No legacy table; ensure schema will be created on first ingestion per game.
        logger.info("[StatsStorage] Connected. Columnar stats will be created per-game on demand.");

        scheduleResets();
    }

    public static void shutdown() {
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (Exception ignored) {}
            dataSource = null;
        }
    }

    // Public API called by Redis ingest
    public static void ingestStats(String gameRaw, JsonObject statsByUuid) throws SQLException {
        if (dataSource == null) throw new IllegalStateException("StatsStorage not initialized");
        String game = normalizeIdentifier(gameRaw, 48); // keep room for suffixes in table name
        
        ingestToGame(game, statsByUuid);
        ingestToGame("global", statsByUuid);

        knownGames.add(game);
        knownGames.add("global");
    }

    private static void ingestToGame(String game, JsonObject statsByUuid) throws SQLException {
        Utils.debugLog("[StatsStorage] Ingesting stats for game: " + game + " (" + statsByUuid.size() + " players)");
        ensureTablesForGame(game);

        // Collect all stat keys to ensure columns
        Set<String> statKeys = new HashSet<>();
        for (Map.Entry<String, JsonElement> e : statsByUuid.entrySet()) {
            if (e.getValue() != null && e.getValue().isJsonObject()) {
                for (Map.Entry<String, JsonElement> s : e.getValue().getAsJsonObject().entrySet()) {
                    statKeys.add(normalizeIdentifier(s.getKey(), 64));
                }
            }
        }
        ensureColumnsForGame(game, statKeys);

        // Build and execute batch increments per slice
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            for (Slice slice : Slice.values()) {
                String table = tableName(game, slice);
                // Build INSERT ... ON DUPLICATE KEY UPDATE statement dynamically
                for (Map.Entry<String, JsonElement> e : statsByUuid.entrySet()) {
                    String uuidStr = e.getKey();
                    if (!isValidUuid(uuidStr)) {
                        GameCoordinator.getLoggerInstance().warning("[StatsStorage] Skipping invalid UUID key: " + uuidStr);
                        continue;
                    }
                    JsonObject obj = e.getValue() != null && e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
                    if (obj.entrySet().isEmpty()) continue;

                    StringBuilder cols = new StringBuilder("player_uuid");
                    StringBuilder vals = new StringBuilder("?");
                    StringBuilder updates = new StringBuilder();
                    List<Double> numbers = new ArrayList<>();
                    for (Map.Entry<String, JsonElement> s : obj.entrySet()) {
                        String col = normalizeIdentifier(s.getKey(), 64);
                        Double val = coerceToDouble(s.getValue());
                        if (val == null) continue; // ignore non-numeric
                        cols.append(",`").append(col).append("`");
                        vals.append(",?");
                        numbers.add(val);
                        if (updates.length() > 0) updates.append(',');
                        updates.append('`').append(col).append('`').append(" = COALESCE(`").append(col).append("`,0) + VALUES(`").append(col).append("`)");
                    }
                    if (numbers.isEmpty()) continue;
                    String sql = "INSERT INTO `" + table + "` (" + cols + ") VALUES (" + vals + ") ON DUPLICATE KEY UPDATE " + updates + ", updated_at = CURRENT_TIMESTAMP";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        int idx = 1;
                        ps.setString(idx++, uuidStr);
                        for (Double d : numbers) {
                            ps.setDouble(idx++, d);
                        }
                        ps.executeUpdate();
                    }
                }
            }
            c.commit();
        }
    }

    public static Double getStat(String uuid, String gameRaw, String statKey, Slice slice) {
        if (dataSource == null) return 0.0;
        String game = normalizeIdentifier(gameRaw, 48);
        String table = tableName(game, slice);
        String col = normalizeIdentifier(statKey, 64);
        
        try (Connection c = dataSource.getConnection()) {
            // Check if column exists first to avoid SQLException
            Set<String> existing = getExistingColumns(c, table);
            if (!existing.contains(col)) return 0.0;

            String sql = "SELECT `" + col + "` FROM `" + table + "` WHERE player_uuid = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getDouble(1);
                    }
                }
            }
        } catch (SQLException e) {
            GameCoordinator.getLoggerInstance().warning("[StatsStorage] Failed to fetch stat " + statKey + " for " + uuid + " in " + game + ": " + e.getMessage());
        }
        return 0.0;
    }

    // Ensure per-game tables exist
    private static void ensureTablesForGame(String game) throws SQLException {
        synchronized (schemaLocks.computeIfAbsent(game, k -> new Object())) {
            try (Connection c = dataSource.getConnection()) {
                for (Slice slice : Slice.values()) {
                    String table = tableName(game, slice);
                    try (Statement st = c.createStatement()) {
                        st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + table + "` (" +
                                "player_uuid CHAR(36) NOT NULL, " +
                                "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                                "PRIMARY KEY (player_uuid)" +
                                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
                    }
                }
            }
        }
    }

    // Ensure columns exist for all slices
    private static void ensureColumnsForGame(String game, Set<String> keys) throws SQLException {
        if (keys == null || keys.isEmpty()) return;
        synchronized (schemaLocks.computeIfAbsent(game, k -> new Object())) {
            try (Connection c = dataSource.getConnection()) {
                for (Slice slice : Slice.values()) {
                    String table = tableName(game, slice);
                    Set<String> existing = getExistingColumns(c, table);
                    for (String key : keys) {
                        if (!existing.contains(key)) {
                            try (Statement st = c.createStatement()) {
                                st.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN `" + key + "` DOUBLE NOT NULL DEFAULT 0");
                            }
                        }
                    }
                }
            }
        }
    }

    private static Set<String> getExistingColumns(Connection c, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, null)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                out.add(col);
            }
        }
        return out;
    }

    private static String tableName(String game, Slice slice) {
        String base = normalizeIdentifier(game, 48);
        switch (slice) {
            case TOTAL: return base + "_stats_total";
            case WEEKLY: return base + "_stats_weekly";
            case MONTHLY: return base + "_stats_monthly";
            case YEARLY: return base + "_stats_yearly";
            default: return base + "_stats_total";
        }
    }

    private static String normalizeIdentifier(String in, int maxLen) {
        if (in == null) return "unknown";
        String s = in.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (s.isEmpty()) s = "unknown";
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        // trim leading/trailing underscores
        s = s.replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "x";
        return s;
    }

    private static boolean isValidUuid(String s) {
        try { UUID.fromString(s); return true; } catch (Exception e) { return false; }
    }

    private static boolean isNonEmpty(String s) { return s != null && !s.isEmpty(); }

    private static Double coerceToDouble(JsonElement el) {
        if (el == null) return null;
        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                return el.getAsDouble();
            }
            // if string number
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return Double.parseDouble(el.getAsString());
            }
        } catch (Exception ignored) {}
        return null;
    }

    // Scheduling of resets with backups
    private static void scheduleResets() {
        // Weekly
        scheduleNext(Slice.WEEKLY);
        scheduleNext(Slice.MONTHLY);
        scheduleNext(Slice.YEARLY);
    }

    private static void scheduleNext(Slice slice) {
        long delayTicks = ticksUntilNext(slice);
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                backupAndReset(slice);
            } catch (Exception e) {
                GameCoordinator.getLoggerInstance().severe("[StatsStorage] Reset for " + slice + " failed: " + e.getMessage());
            } finally {
                // reschedule next
                scheduleNext(slice);
            }
        }, delayTicks);
    }

    private static long ticksUntilNext(Slice slice) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime target;
        switch (slice) {
            case WEEKLY:
                target = now.with(java.time.DayOfWeek.MONDAY).withHour(0).withMinute(0).withSecond(0).withNano(0);
                if (!target.isAfter(now)) target = target.plusWeeks(1);
                break;
            case MONTHLY:
                target = now.with(TemporalAdjusters.firstDayOfNextMonth()).withHour(0).withMinute(0).withSecond(0).withNano(0);
                break;
            case YEARLY:
                target = now.with(TemporalAdjusters.firstDayOfNextYear()).withHour(0).withMinute(0).withSecond(0).withNano(0);
                break;
            default:
                target = now.plusSeconds(60);
        }
        long seconds = java.time.Duration.between(now, target).getSeconds();
        return Math.max(20L, seconds * 20L); // at least 1 second
    }

    private static void backupAndReset(Slice slice) throws Exception {
        // For each known game, backup and truncate the slice table
        for (String game : new ArrayList<>(knownGames)) {
            String table = tableName(game, slice);
            // Backup
            try (Connection c = dataSource.getConnection()) {
                List<String> statCols = new ArrayList<>();
                DatabaseMetaData meta = c.getMetaData();
                try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, null)) {
                    while (rs.next()) {
                        String col = rs.getString("COLUMN_NAME");
                        if (!"player_uuid".equalsIgnoreCase(col) && !"updated_at".equalsIgnoreCase(col)) {
                            statCols.add(col);
                        }
                    }
                }
                if (!statCols.isEmpty()) {
                    String sql = "SELECT player_uuid, " + String.join(",", backtick(statCols)) + " FROM `" + table + "`";
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        writeBackupJson(game, slice, statCols, rs);
                    }
                } else {
                    // still write an empty file to mark reset
                    writeBackupJson(game, slice, Collections.emptyList(), null);
                }
            }
            // Reset
            try (Connection c2 = dataSource.getConnection(); Statement st2 = c2.createStatement()) {
                st2.executeUpdate("TRUNCATE TABLE `" + table + "`");
            }
            GameCoordinator.getLoggerInstance().info("[StatsStorage] Reset slice " + slice + " for game=" + game);
        }
    }

    private static List<String> backtick(List<String> cols) {
        List<String> out = new ArrayList<>(cols.size());
        for (String c : cols) out.add("`" + c + "`");
        return out;
    }

    private static void writeBackupJson(String game, Slice slice, List<String> statCols, ResultSet rs) throws Exception {
        File folder = new File(plugin.getDataFolder(), "Stats-Data-Historical/" + game + "/" + slice.name().toLowerCase(Locale.ROOT));
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Failed to create backup directory: " + folder);
        }
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        File out = new File(folder, ts + ".json");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write("{\n");
            boolean firstRow = true;
            if (rs != null) {
                while (rs.next()) {
                    String uuid = rs.getString(1);
                    if (!firstRow) w.write(",\n");
                    firstRow = false;
                    w.write("  \""); w.write(uuid); w.write("\": {");
                    boolean firstCol = true;
                    for (int i = 0; i < statCols.size(); i++) {
                        String col = statCols.get(i);
                        double val = rs.getDouble(i + 2);
                        if (!firstCol) w.write(", ");
                        firstCol = false;
                        w.write("\""); w.write(col); w.write("\": ");
                        w.write(Double.toString(val));
                    }
                    w.write("}");
                }
            }
            w.write("\n}\n");
        }
    }
}
