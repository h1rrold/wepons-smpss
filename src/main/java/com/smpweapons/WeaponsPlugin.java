package com.smpweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class WeaponsPlugin extends JavaPlugin {
    public Items items;
    public DataManager data;
    public Cooldowns cooldowns;
    public Abilities abilities;
    public WeaponManager weapons;
    public Passives passives;
    public NamespacedKey rerollRecipeKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        items = new Items(this);
        data = new DataManager(this);
        data.load();
        cooldowns = new Cooldowns();
        abilities = new Abilities(this);
        weapons = new WeaponManager(this);
        passives = new Passives(this);
        rerollRecipeKey = new NamespacedKey(this, "weapon_reroller");

        registerRecipe();

        Commands commands = new Commands(this);
        for (String name : new String[]{"trust", "untrust", "start", "end", "reroll", "upgrade", "weapon", "heavyhitter"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(commands);
                pc.setTabCompleter(commands);
            }
        }
        Bukkit.getPluginManager().registerEvents(commands, this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AbilityListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(passives, this);
        passives.start();

        // autosave every 5 minutes
        Bukkit.getScheduler().runTaskTimer(this, () -> data.save(), 6000L, 6000L);

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipe(rerollRecipeKey);
        }
        getLogger().info("SMPWeapons enabled. SMP active: " + data.smpActive);
    }

    @Override
    public void onDisable() {
        if (passives != null) passives.shutdown();
        if (abilities != null) abilities.shutdown();
        if (data != null) data.save();
        if (rerollRecipeKey != null) Bukkit.removeRecipe(rerollRecipeKey);
    }

    private void registerRecipe() {
        Bukkit.removeRecipe(rerollRecipeKey);
        List<Material> mats = new ArrayList<>();
        for (Weapon w : Weapon.values()) {
            if (w.isRandomPool() && !mats.contains(w.material)) mats.add(w.material);
        }
        ShapedRecipe r = new ShapedRecipe(rerollRecipeKey, items.createReroller());
        // Blaze rod | empty | Blaze rod
        // Ender pearl | your weapon | Ender pearl
        // Breeze rod | empty | Breeze rod
        r.shape("B B", "EWE", "R R");
        r.setIngredient('B', Material.BLAZE_ROD);
        r.setIngredient('E', Material.ENDER_PEARL);
        r.setIngredient('R', Material.BREEZE_ROD);
        r.setIngredient('W', new RecipeChoice.MaterialChoice(mats));
        Bukkit.addRecipe(r);
    }

    // ------------------------------------------------------------------ helpers

    public boolean smpActive() {
        return data != null && data.smpActive;
    }

    public Component color(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
    }

    public void msg(CommandSender s, String text) {
        if (s == null) return;
        s.sendMessage(color("&8[&6Weapons&8] &7" + text));
    }

    /** Cooldown in seconds for a weapon's ability, with config override + the Tier II reduction. */
    public double cooldownSeconds(Weapon w, int ability, int tier) {
        int def = ability == 1 ? w.cooldown1 : w.cooldown2;
        double base = getConfig().getDouble("cooldowns." + w.id + ".ability" + ability, def);
        if (tier >= 2) base *= getConfig().getDouble("tier2-cooldown-multiplier", 0.75);
        return base;
    }

    public void sendResourcePack(Player p) {
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) return;
        String sha = getConfig().getString("resource-pack.sha1", "");
        boolean force = getConfig().getBoolean("resource-pack.force", false);
        byte[] hash = null;
        if (sha != null && sha.length() == 40) {
            hash = new byte[20];
            try {
                for (int i = 0; i < 20; i++) {
                    hash[i] = (byte) Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16);
                }
            } catch (NumberFormatException ex) {
                hash = null;
            }
        }
        p.setResourcePack(url, hash, force);
    }
}
