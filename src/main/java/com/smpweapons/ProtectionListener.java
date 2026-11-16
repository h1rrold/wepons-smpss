package com.smpweapons;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Iterator;
import java.util.List;

/**
 * Weapons can't be lost: no dropping, no chests / shulkers / ender chests / bundles / hoppers / item frames.
 * Anvils, enchanting tables, crafting grids (for the reroller) and grindstones are the only allowed screens.
 */
public final class ProtectionListener implements Listener {
    private final WeaponsPlugin plugin;

    public ProtectionListener(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean soul(ItemStack it) {
        return it != null && it.getType() != Material.AIR && plugin.items.isSoulbound(it);
    }

    private static boolean isBundle(ItemStack it) {
        return it != null && it.getType().name().endsWith("BUNDLE");
    }

    private void deny(HumanEntity h) {
        if (h instanceof Player) {
            ((Player) h).sendActionBar(plugin.color("&cYour weapon is soulbound \u2014 it can't leave your inventory."));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        if (soul(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        HumanEntity who = e.getWhoClicked();
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        ItemStack hotbar = null;
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
            hotbar = who.getInventory().getItem(e.getHotbarButton());
        }
        boolean offhand = e.getClick() == ClickType.SWAP_OFFHAND && soul(who.getInventory().getItemInOffHand());
        boolean cursorSB = soul(cursor);
        boolean currentSB = soul(current);
        boolean hotbarSB = soul(hotbar);
        if (!cursorSB && !currentSB && !hotbarSB && !offhand) return;

        // dropping with Q / ctrl+Q / clicking outside the window with the weapon on the cursor
        if (currentSB && (e.getClick() == ClickType.DROP || e.getClick() == ClickType.CONTROL_DROP)) {
            e.setCancelled(true);
            deny(who);
            return;
        }
        if (cursorSB && e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE) {
            e.setCancelled(true);
            deny(who);
            return;
        }
        // bundles / "sacks"
        if ((cursorSB && isBundle(current)) || (currentSB && isBundle(cursor))) {
            e.setCancelled(true);
            deny(who);
            return;
        }
        Inventory top = e.getView().getTopInventory();
        if (WeaponManager.ALLOWED_SCREENS.contains(top.getType())) return;

        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        // putting a weapon into a container: cursor placement, hotbar/offhand swap, or shift-click from the bottom
        if (clickedTop && (cursorSB || hotbarSB || offhand)) {
            e.setCancelled(true);
            deny(who);
            return;
        }
        if (!clickedTop && currentSB && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
            deny(who);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!soul(e.getOldCursor())) return;
        Inventory top = e.getView().getTopInventory();
        if (WeaponManager.ALLOWED_SCREENS.contains(top.getType())) return;
        int size = top.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < size) {
                e.setCancelled(true);
                deny(e.getWhoClicked());
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHopper(InventoryMoveItemEvent e) {
        if (soul(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (!soul(hand)) return;
        if (e.getRightClicked() instanceof ItemFrame || e.getRightClicked() instanceof ArmorStand
                || e.getRightClicked() instanceof Allay) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (soul(e.getPlayerItem())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    // ------------------------------------------------------------------ crafting (reroller only)

    private boolean isRerollRecipe(Recipe r) {
        return r instanceof Keyed && ((Keyed) r).getKey().equals(plugin.rerollRecipeKey);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r == null) return;
        ItemStack[] matrix = e.getInventory().getMatrix();
        if (isRerollRecipe(r)) {
            boolean ok = false;
            if (matrix.length >= 9 && e.getView().getPlayer() instanceof Player) {
                Player p = (Player) e.getView().getPlayer();
                ItemStack mid = matrix[4];
                Weapon w = plugin.items.weaponOf(mid);
                java.util.UUID owner = plugin.items.ownerOf(mid);
                ok = w != null && w != Weapon.HEAVY && plugin.smpActive()
                        && (owner == null || owner.equals(p.getUniqueId()));
            }
            if (!ok) e.getInventory().setResult(null);
            return;
        }
        for (ItemStack it : matrix) {
            if (soul(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(CraftItemEvent e) {
        Recipe r = e.getRecipe();
        if (isRerollRecipe(r)) return;
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (soul(it)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ misc item rules

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        ItemStack it = e.getItem().getItemStack();
        if (plugin.items.isKiwi(it)) {
            boolean tropical = plugin.data.get(p.getUniqueId()).weapon == Weapon.TROPICAL;
            if (!tropical) {
                e.setCancelled(true);
                return;
            }
            int have = 0;
            for (ItemStack c : p.getInventory().getContents()) if (plugin.items.isKiwi(c)) have += c.getAmount();
            if (have + it.getAmount() > 90) e.setCancelled(true);
            return;
        }
        if (plugin.getConfig().getBoolean("remove-other-maces", true) && it.getType() == Material.MACE
                && plugin.items.weaponOf(it) != Weapon.HEAVY) {
            e.setCancelled(true);
            e.getItem().remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        if (!plugin.getConfig().getBoolean("remove-other-maces", true)) return;
        List<ItemStack> loot = e.getLoot();
        Iterator<ItemStack> it = loot.iterator();
        while (it.hasNext()) {
            ItemStack s = it.next();
            if (s != null && s.getType() == Material.MACE) it.remove();
        }
    }
}
