package com.smpweapons;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The 7 random weapons plus the unique Heavy Hitter mace. */
public enum Weapon {
    BEAMER("beamer_sword", "Beamer Sword", NamedTextColor.LIGHT_PURPLE, Material.NETHERITE_SWORD, "Void Beam", "Rift Dash", 30, 30),
    TROPICAL("tropical_bow_staff", "Tropical Bow Staff", NamedTextColor.GREEN, Material.NETHERITE_SWORD, "Vine Snare", "Verdant Bubble", 30, 50),
    ICE("ice_axe", "Ice Axe", NamedTextColor.AQUA, Material.NETHERITE_AXE, "Deep Freeze", "Double Hit", 50, 0),
    DYNASTY("dynasty_sword", "Dynasty Sword", NamedTextColor.GOLD, Material.NETHERITE_SWORD, "Coin Barrage", "Imperial Spin", 0, 40),
    INFERNO("inferno_sword", "Inferno Sword", NamedTextColor.RED, Material.NETHERITE_SWORD, "Lava Wall", "Blaze Circle", 30, 45),
    HELLSFIRE("hellsfire_axe", "Hellsfire Axe", NamedTextColor.DARK_RED, Material.NETHERITE_AXE, "Inferno Slam", "Hellfire Cleave", 45, 35),
    DRAGON("dragons_fang", "Dragon's Fang", NamedTextColor.DARK_PURPLE, Material.NETHERITE_SWORD, "Dragon's Breath", "Mini Dragon", 30, 40),
    HEAVY("heavy_hitter", "Heavy Hitter", NamedTextColor.GOLD, Material.MACE, "Cataclysm", "Titan's Wrath", 50, 65);

    public final String id;
    public final String display;
    public final NamedTextColor color;
    public final Material material;
    public final String ability1;
    public final String ability2;
    public final int cooldown1;
    public final int cooldown2;

    Weapon(String id, String display, NamedTextColor color, Material material,
           String ability1, String ability2, int cooldown1, int cooldown2) {
        this.id = id;
        this.display = display;
        this.color = color;
        this.material = material;
        this.ability1 = ability1;
        this.ability2 = ability2;
        this.cooldown1 = cooldown1;
        this.cooldown2 = cooldown2;
    }

    /** True for the 7 weapons that are handed out randomly (everything except the mace). */
    public boolean isRandomPool() {
        return this != HEAVY;
    }

    public String abilityName(int n) {
        return n == 1 ? ability1 : ability2;
    }

    public static Weapon fromId(String s) {
        if (s == null || s.isBlank()) return null;
        String q = s.toLowerCase().replace(' ', '_').replace("'", "").replace("-", "_");
        for (Weapon w : values()) {
            if (w.id.equals(q) || w.name().equalsIgnoreCase(q)) return w;
        }
        if (q.equals("mace") || q.equals("hammer") || q.equals("hh")) return HEAVY;
        // forgiving aliases (beamer, ice, dragon, mace, ...)
        for (Weapon w : values()) {
            if (w.id.startsWith(q) || w.id.replace("_", "").startsWith(q.replace("_", ""))) return w;
        }
        return null;
    }

    public static Weapon random(Weapon exclude) {
        List<Weapon> pool = new ArrayList<>();
        for (Weapon w : values()) {
            if (w.isRandomPool() && w != exclude) pool.add(w);
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
