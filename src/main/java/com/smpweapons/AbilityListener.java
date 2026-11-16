package com.smpweapons;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Right-click = ability 1, sneak + right-click = ability 2. Also enforces the vine-trap "can only eat" rule. */
public final class AbilityListener implements Listener {
    private final WeaponsPlugin plugin;

    public AbilityListener(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean trappedNoInteract(Player p) {
        return plugin.abilities.noInteractUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();

        if (trappedNoInteract(p)) {
            if (item == null || !item.getType().isEdible()) {
                e.setUseItemInHand(Event.Result.DENY);
                e.setUseInteractedBlock(Event.Result.DENY);
                e.setCancelled(true);
            }
            return;
        }
        if (item == null || item.getType() == Material.AIR) return;
        if (p.getGameMode() == GameMode.SPECTATOR || p.isDead()) return;

        if (plugin.items.isReroller(item)) {
            e.setCancelled(true);
            if (!plugin.smpActive()) {
                plugin.msg(p, "&cThe SMP hasn't started yet.");
                return;
            }
            Weapon nw = plugin.weapons.reroll(p);
            plugin.msg(p, "&dRerolled! &7You are now wielding the &e" + nw.display + "&7.");
            return;
        }

        Weapon w = plugin.items.weaponOf(item);
        if (w == null) return;
        // let people still open chests, doors, crafting tables... with a weapon in hand (unless sneaking)
        if (a == Action.RIGHT_CLICK_BLOCK && !p.isSneaking() && e.getClickedBlock() != null
                && e.getClickedBlock().getType().isInteractable()) {
            return;
        }
        e.setCancelled(true); // stops axes stripping logs, etc.
        plugin.abilities.use(p, w, p.isSneaking() ? 2 : 1);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (trappedNoInteract(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (trappedNoInteract(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (trappedNoInteract(e.getPlayer())) e.setCancelled(true);
    }
}
