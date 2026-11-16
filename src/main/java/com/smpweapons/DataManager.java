package com.smpweapons;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** data.yml persistence: per-player weapon/tier/kills/trust list, Heavy Hitter holder, SMP on/off. */
public final class DataManager {
    private final WeaponsPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerData> players = new HashMap<>();

    public UUID heavyHolder;
    public boolean smpActive;

    public DataManager(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public PlayerData get(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerData());
    }

    public boolean has(UUID id) {
        return players.containsKey(id);
    }

    public Map<UUID, PlayerData> all() {
        return players;
    }

    public void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        smpActive = yml.getBoolean("smp-active", plugin.getConfig().getBoolean("auto-start", false));
        String hh = yml.getString("heavy-holder");
        if (hh != null && !hh.isEmpty()) {
            try {
                heavyHolder = UUID.fromString(hh);
            } catch (IllegalArgumentException ignored) {
                heavyHolder = null;
            }
        }
        ConfigurationSection sec = yml.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection ps = sec.getConfigurationSection(key);
            if (ps == null) continue;
            PlayerData pd = new PlayerData();
            pd.weapon = Weapon.fromId(ps.getString("weapon"));
            if (pd.weapon == Weapon.HEAVY) pd.weapon = null;
            pd.tier = Math.max(1, Math.min(2, ps.getInt("tier", 1)));
            pd.kills = ps.getInt("kills", 0);
            pd.lastName = ps.getString("name");
            for (String t : ps.getStringList("trusted")) {
                try {
                    pd.trusted.add(UUID.fromString(t));
                } catch (IllegalArgumentException ignored) {
                }
            }
            players.put(id, pd);
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("smp-active", smpActive);
        yml.set("heavy-holder", heavyHolder == null ? "" : heavyHolder.toString());
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            String base = "players." + e.getKey();
            PlayerData pd = e.getValue();
            yml.set(base + ".weapon", pd.weapon == null ? "" : pd.weapon.id);
            yml.set(base + ".tier", pd.tier);
            yml.set(base + ".kills", pd.kills);
            yml.set(base + ".name", pd.lastName);
            List<String> trusted = new ArrayList<>();
            for (UUID u : pd.trusted) trusted.add(u.toString());
            yml.set(base + ".trusted", trusted);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }
}
