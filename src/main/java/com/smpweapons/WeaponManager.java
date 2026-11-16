package com.smpweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Weapon lifecycle: handing out, rerolling, upgrading, de-duplicating and the Heavy Hitter. */
public final class WeaponManager {
    /** Inventory screens where a soulbound weapon is allowed to sit (anvil, enchanting table, crafting...). */
    public static final Set<InventoryType> ALLOWED_SCREENS = EnumSet.of(
            InventoryType.CRAFTING, InventoryType.WORKBENCH, InventoryType.ANVIL,
            InventoryType.ENCHANTING, InventoryType.GRINDSTONE, InventoryType.CREATIVE);

    private final WeaponsPlugin plugin;
    private final Map<UUID, Integer> missing = new HashMap<>();

    public WeaponManager(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private Items items() {
        return plugin.items;
    }

    // ------------------------------------------------------------------ scanning

    /** Every stack the player could be "holding": inventory, cursor and (allowed) open crafting-style screens. */
    public List<ItemStack> carried(Player p) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) if (it != null) out.add(it);
        ItemStack cur = p.getItemOnCursor();
        if (cur != null && cur.getType() != Material.AIR) out.add(cur);
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getType() != InventoryType.CRAFTING && ALLOWED_SCREENS.contains(top.getType())) {
            for (ItemStack it : top.getContents()) if (it != null) out.add(it);
        }
        return out;
    }

    public boolean hasWeaponItem(Player p) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        for (ItemStack it : carried(p)) {
            Weapon w = items().weaponOf(it);
            if (w != null && w != Weapon.HEAVY && w == pd.weapon) return true;
            if (items().isReroller(it)) return true;
        }
        return false;
    }

    public boolean hasHeavyItem(Player p) {
        for (ItemStack it : carried(p)) {
            if (items().weaponOf(it) == Weapon.HEAVY) return true;
        }
        return false;
    }

    /** Removes weapon items from the main inventory. Returns how many stacks were removed. */
    public int removeWeapons(Player p, boolean includeHeavy, boolean includeRerollers) {
        PlayerInventory inv = p.getInventory();
        int removed = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            Weapon w = items().weaponOf(it);
            boolean kill = false;
            if (w != null && (w != Weapon.HEAVY || includeHeavy)) kill = true;
            if (includeRerollers && items().isReroller(it)) kill = true;
            if (kill) {
                inv.setItem(i, null);
                removed++;
            }
        }
        ItemStack cur = p.getItemOnCursor();
        Weapon cw = items().weaponOf(cur);
        if (cw != null && (cw != Weapon.HEAVY || includeHeavy)) {
            p.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }

    /** Gives an item. If the inventory is full a normal stack is dropped to make room - soulbound items never drop. */
    public void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        if (left.isEmpty()) return;
        PlayerInventory inv = p.getInventory();
        for (int i = 35; i >= 0; i--) {
            ItemStack it = inv.getItem(i);
            if (it != null && !items().isSoulbound(it)) {
                p.getWorld().dropItemNaturally(p.getLocation(), it);
                inv.setItem(i, item);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ handing out

    public void assignRandom(Player p) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        pd.lastName = p.getName();
        pd.weapon = Weapon.random(null);
        pd.tier = 1;
        pd.kills = 0;
        removeWeapons(p, false, true);
        give(p, items().createWeapon(pd.weapon, pd.tier, p.getUniqueId()));
        announceNewWeapon(p, pd.weapon);
        plugin.data.save();
    }

    public void giveWeapon(Player p, Weapon w, boolean keepTier) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        pd.lastName = p.getName();
        pd.weapon = w;
        if (!keepTier) {
            pd.tier = 1;
            pd.kills = 0;
        }
        removeWeapons(p, false, true);
        give(p, items().createWeapon(w, pd.tier, p.getUniqueId()));
        announceNewWeapon(p, w);
        plugin.data.save();
    }

    /** Reroll to a different weapon, keeping tier/kills. */
    public Weapon reroll(Player p) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        Weapon nw = Weapon.random(pd.weapon);
        giveWeapon(p, nw, true);
        Fx.sound(p.getLocation(), "reroll", "block.enchantment_table.use", 1.4f, 1f);
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 80, 0.5, 0.8, 0.5, 0.6);
        return nw;
    }

    private void announceNewWeapon(Player p, Weapon w) {
        p.showTitle(Title.title(
                Component.text(w.display, w.color),
                plugin.color("&7Tier I \u2014 " + w.ability1 + " (right-click)"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(500))));
        Fx.sound(p.getLocation(), "passive_chime", "entity.player.levelup", 1f, 1f);
    }

    /** Called on join/respawn: makes sure an active player holds their weapon. */
    public void ensureWeapon(Player p) {
        if (!plugin.smpActive()) return;
        UUID id = p.getUniqueId();
        PlayerData pd = plugin.data.get(id);
        pd.lastName = p.getName();
        if (pd.weapon == null) {
            assignRandom(p);
            return;
        }
        if (!hasWeaponItem(p)) {
            give(p, items().createWeapon(pd.weapon, pd.tier, id));
            plugin.msg(p, "&7Your &e" + pd.weapon.display + "&7 was returned to you.");
        }
        missing.remove(id);
    }

    /**
     * Periodic housekeeping (every ~2 s per player):
     * strips duplicate/foreign weapons, other maces, illegal kiwis and re-gives a lost weapon.
     */
    public void housekeeping(Player p) {
        UUID id = p.getUniqueId();
        PlayerData pd = plugin.data.get(id);
        PlayerInventory inv = p.getInventory();
        boolean keptWeapon = false;
        boolean keptHeavy = false;
        int kiwis = 0;
        boolean removeMaces = plugin.getConfig().getBoolean("remove-other-maces", true);
        boolean mayHoldKiwi = pd.weapon == Weapon.TROPICAL;

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            Weapon w = items().weaponOf(it);
            if (w == Weapon.HEAVY) {
                boolean valid = id.equals(plugin.data.heavyHolder) && !keptHeavy;
                if (valid) keptHeavy = true;
                else {
                    inv.setItem(i, null); // ghosted: someone else already owns the one and only mace
                }
                continue;
            }
            if (w != null) {
                UUID owner = items().ownerOf(it);
                boolean valid = plugin.smpActive() && w == pd.weapon && !keptWeapon && (owner == null || owner.equals(id));
                if (valid) keptWeapon = true;
                else if (plugin.smpActive()) inv.setItem(i, null);
                continue;
            }
            if (removeMaces && it.getType() == Material.MACE) {
                inv.setItem(i, null);
                continue;
            }
            if (items().isKiwi(it)) {
                if (!mayHoldKiwi) {
                    inv.setItem(i, null);
                } else {
                    int room = 90 - kiwis;
                    if (room <= 0) inv.setItem(i, null);
                    else if (it.getAmount() > room) {
                        it.setAmount(room);
                        kiwis += room;
                    } else kiwis += it.getAmount();
                }
            }
        }

        if (plugin.smpActive() && pd.weapon != null && !keptWeapon && !hasWeaponItem(p)) {
            int miss = missing.merge(id, 1, Integer::sum);
            if (miss >= 2) {
                missing.remove(id);
                give(p, items().createWeapon(pd.weapon, pd.tier, id));
                plugin.msg(p, "&7Your &e" + pd.weapon.display + "&7 was returned to you.");
            }
        } else {
            missing.remove(id);
        }
    }

    // ------------------------------------------------------------------ tiers

    public void refreshItems(Player p) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        for (ItemStack it : carried(p)) {
            if (items().isWeapon(it)) items().applyTier(it, pd.tier);
        }
    }

    public void setTier(Player p, int tier, boolean announce) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        int old = pd.tier;
        pd.tier = Math.max(1, Math.min(2, tier));
        refreshItems(p);
        if (announce && pd.tier > old) {
            p.showTitle(Title.title(
                    Component.text("WEAPON UPGRADED", net.kyori.adventure.text.format.NamedTextColor.GOLD),
                    plugin.color("&7Tier II unlocked \u2014 sneak + right-click for your second ability"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(600))));
            Fx.sound(p.getLocation(), "upgrade", "ui.toast.challenge_complete", 1.5f, 1f);
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 0.9, 0.5, 0.4);
            Bukkit.broadcast(plugin.color("&8[&6Weapons&8] &e" + p.getName() + "&7's weapon has been &6upgraded&7!"));
        }
        plugin.data.save();
    }

    public void addKill(Player killer) {
        PlayerData pd = plugin.data.get(killer.getUniqueId());
        pd.kills++;
        if (pd.kills >= 3 && pd.tier < 2) setTier(killer, 2, true);
        else plugin.msg(killer, "&7Kills: &e" + pd.kills + (pd.tier < 2 ? "&7/3 until your weapon upgrades" : ""));
    }

    /** Death rule: upgrade is wiped, kill counter reset. */
    public void resetProgress(Player p) {
        PlayerData pd = plugin.data.get(p.getUniqueId());
        pd.tier = 1;
        pd.kills = 0;
    }

    // ------------------------------------------------------------------ heavy hitter

    public void giveHeavyHitter(Player target) {
        UUID old = plugin.data.heavyHolder;
        if (old != null && !old.equals(target.getUniqueId())) {
            Player prev = Bukkit.getPlayer(old);
            if (prev != null) {
                PlayerInventory inv = prev.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    if (items().weaponOf(inv.getItem(i)) == Weapon.HEAVY) inv.setItem(i, null);
                }
            }
        }
        // remove any copies the target already has, then place one in their main hand
        PlayerInventory inv = target.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (items().weaponOf(inv.getItem(i)) == Weapon.HEAVY) inv.setItem(i, null);
        }
        plugin.data.heavyHolder = target.getUniqueId();
        PlayerData pd = plugin.data.get(target.getUniqueId());
        ItemStack mace = items().createWeapon(Weapon.HEAVY, pd.tier, target.getUniqueId());
        ItemStack inHand = inv.getItemInMainHand();
        if (inHand != null && inHand.getType() != Material.AIR) {
            Map<Integer, ItemStack> left = inv.addItem(inHand.clone());
            if (!left.isEmpty()) {
                // no room: swap with the offhand instead of dropping anything important
                ItemStack off = inv.getItemInOffHand();
                if (off == null || off.getType() == Material.AIR) inv.setItemInOffHand(inHand.clone());
                else if (!items().isSoulbound(inHand)) target.getWorld().dropItemNaturally(target.getLocation(), inHand.clone());
            }
        }
        inv.setItemInMainHand(mace);
        Fx.sound(target.getLocation(), "hh_claim", "item.trident.thunder", 2f, 0.8f);
        target.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, target.getLocation().add(0, 1, 0), 80, 0.6, 1, 0.6, 0.5);
        plugin.data.save();
    }

    public void removeHeavyHitter() {
        UUID old = plugin.data.heavyHolder;
        plugin.data.heavyHolder = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (items().weaponOf(inv.getItem(i)) == Weapon.HEAVY) inv.setItem(i, null);
            }
            if (items().weaponOf(p.getItemOnCursor()) == Weapon.HEAVY) p.setItemOnCursor(null);
        }
        plugin.data.save();
    }
}
