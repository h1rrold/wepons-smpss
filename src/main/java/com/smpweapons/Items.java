package com.smpweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds and identifies every custom item (weapons, kiwi fruit, reroller, coin). */
public final class Items {
    private final WeaponsPlugin plugin;
    public final NamespacedKey keyType;
    public final NamespacedKey keyOwner;
    public final NamespacedKey keySoul;
    public final NamespacedKey keyKiwi;
    public final NamespacedKey keyReroll;

    public Items(WeaponsPlugin plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "weapon_type");
        this.keyOwner = new NamespacedKey(plugin, "weapon_owner");
        this.keySoul = new NamespacedKey(plugin, "soulbound");
        this.keyKiwi = new NamespacedKey(plugin, "kiwi_fruit");
        this.keyReroll = new NamespacedKey(plugin, "reroller");
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static NamespacedKey model(String name) {
        return NamespacedKey.fromString("weapons:" + name);
    }

    // ------------------------------------------------------------------ weapons

    public ItemStack createWeapon(Weapon w, int tier, UUID owner) {
        ItemStack item = new ItemStack(w.material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(w.display, w.color).decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyType, PersistentDataType.STRING, w.id);
        pdc.set(keySoul, PersistentDataType.BYTE, (byte) 1);
        if (owner != null) pdc.set(keyOwner, PersistentDataType.STRING, owner.toString());
        item.setItemMeta(meta);
        applyTier(item, tier);
        return item;
    }

    /** Re-skins an existing weapon stack for the given tier. Keeps name, enchants and the rest. */
    public void applyTier(ItemStack item, int tier) {
        Weapon w = weaponOf(item);
        if (w == null) return;
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model(w.id + "_t" + (tier >= 2 ? 2 : 1)));
        meta.setEnchantmentGlintOverride(tier >= 2 ? Boolean.TRUE : null);
        meta.lore(lore(w, tier));
        item.setItemMeta(meta);
    }

    private List<Component> lore(Weapon w, int tier) {
        List<Component> l = new ArrayList<>();
        l.add(line(tier >= 2 ? "Tier II \u2726 Awakened" : "Tier I", tier >= 2 ? NamedTextColor.GOLD : NamedTextColor.GRAY));
        l.add(Component.empty());
        l.add(line("Ability 1 \u2014 " + w.ability1, NamedTextColor.YELLOW));
        l.add(line("  Right-click", NamedTextColor.DARK_GRAY));
        if (w == Weapon.ICE) {
            l.add(line("Ability 2 \u2014 " + w.ability2 + (tier >= 2 ? "" : " (Tier II)"),
                    tier >= 2 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
            l.add(line("  Automatic, every 6th hit", NamedTextColor.DARK_GRAY));
        } else {
            l.add(line("Ability 2 \u2014 " + w.ability2 + (tier >= 2 ? "" : " (Tier II)"),
                    tier >= 2 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY));
            l.add(line("  Sneak + Right-click", NamedTextColor.DARK_GRAY));
        }
        l.add(Component.empty());
        l.add(line(passive(w), NamedTextColor.AQUA));
        l.add(Component.empty());
        l.add(line("Soulbound", NamedTextColor.DARK_PURPLE));
        return l;
    }

    private static String passive(Weapon w) {
        switch (w) {
            case BEAMER:
                return "Passive: no fall damage";
            case TROPICAL:
                return "Passive: crouch to turn apples into Kiwi Fruits";
            case ICE:
                return "Passive: frost + powder snow immunity, Speed III in snow biomes";
            case DYNASTY:
                return "Passive: Hero of the Village + double jump";
            case INFERNO:
                return "Passive: fire resistance, Resistance I in the Nether";
            case HELLSFIRE:
                return "Passive: true fire damage (3x longer), fire resistance";
            case DRAGON:
                return "Passive: potions last 10 minutes (not healing ones)";
            default:
                return "Colossus: Resistance I, heavy knockback, every 3rd hit shockwaves";
        }
    }

    public Weapon weaponOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        return id == null ? null : Weapon.fromId(id);
    }

    public boolean isWeapon(ItemStack item) {
        return weaponOf(item) != null;
    }

    public UUID ownerOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isSoulbound(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keySoul, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ kiwi

    public ItemStack createKiwi(int amount) {
        ItemStack item = new ItemStack(Material.APPLE, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Kiwi Fruit", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Eat for +3 absorption hearts", NamedTextColor.GRAY));
        lore.add(line("Only Tropical Bow Staff users can carry these", NamedTextColor.DARK_GREEN));
        meta.lore(lore);
        meta.setMaxStackSize(45);
        meta.setItemModel(model("kiwi_fruit"));
        meta.getPersistentDataContainer().set(keyKiwi, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isKiwi(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyKiwi, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ reroller

    public ItemStack createReroller() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Weapon Reroller", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Right-click to trade your weapon", NamedTextColor.GRAY));
        lore.add(line("for a different random one.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.add(line("Soulbound", NamedTextColor.DARK_PURPLE));
        meta.lore(lore);
        meta.setItemModel(model("reroller"));
        meta.setEnchantmentGlintOverride(Boolean.TRUE);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyReroll, PersistentDataType.BYTE, (byte) 1);
        pdc.set(keySoul, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isReroller(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(keyReroll, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ misc

    public ItemStack createCoin() {
        ItemStack item = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model("coin"));
        item.setItemMeta(meta);
        return item;
    }

    /** The 3D mini Ender Dragon item: wings up (down=false) or wings down (down=true) for the flap animation. */
    public ItemStack createMiniDragon(boolean down) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model(down ? "mini_dragon_down" : "mini_dragon"));
        item.setItemMeta(meta);
        return item;
    }
}
