package net.afterbloom.gameCoordinator;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SupporterConfig {
    private final GameCoordinator plugin;
    private final File file;
    private FileConfiguration config;
    private final Map<String, CustomPlaceholder> customPlaceholders = new HashMap<>();

    public SupporterConfig(GameCoordinator plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "supporter.yml");
        load();
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("supporter.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        customPlaceholders.clear();

        if (config.contains("placeholders")) {
            for (String key : config.getConfigurationSection("placeholders").getKeys(false)) {
                List<String> servers = config.getStringList("placeholders." + key + ".servers");
                List<String> games = config.getStringList("placeholders." + key + ".games");
                customPlaceholders.put(key, new CustomPlaceholder(servers, games));
            }
        }
    }

    public Map<String, CustomPlaceholder> getCustomPlaceholders() {
        return customPlaceholders;
    }

    public static class CustomPlaceholder {
        private final List<String> servers;
        private final List<String> games;

        public CustomPlaceholder(List<String> servers, List<String> games) {
            this.servers = servers != null ? servers : new ArrayList<>();
            this.games = games != null ? games : new ArrayList<>();
        }

        public List<String> getServers() {
            return servers;
        }

        public List<String> getGames() {
            return games;
        }
    }
}
