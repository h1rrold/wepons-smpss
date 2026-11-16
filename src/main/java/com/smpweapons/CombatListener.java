package com.smpweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Join / respawn / death rules, kill tracking + upgrades, passive combat perks (Ice double hit, Colossus, no-fall...). */
public final class CombatListener implements Listener {
    private final WeaponsPlugin plugin;
    private final Map<UUID, Integer> iceHits = new HashMap<>();
    private final Map<UUID, Integer> maceHits = new HashMap<>();

    public CombatListener(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ join / quit / respawn

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        plugin.data.get(p.getUniqueId()).lastName = p.getName();
        p.discoverRecipe(plugin.rerollRecipeKey);
        plugin.sendResourcePack(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.weapons.housekeeping(p);
            plugin.weapons.ensureWeapon(p);
            if (!plugin.smpActive() && p.hasPermission("weapons.admin")) {
                plugin.msg(p, "&eThe SMP hasn't started \u2014 weapons and abilities are off. Run &6/start smp&e when you're ready.");
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.abilities.forget(e.getPlayer().getUniqueId());
        iceHits.remove(e.getPlayer().getUniqueId());
        maceHits.remove(e.getPlayer().getUniqueId());
        plugin.data.save();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.weapons.refreshItems(p);
            plugin.weapons.ensureWeapon(p);
        }, 5L);
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        final Player victim = e.getEntity();
        final Player killer = victim.getKiller();
        final boolean pk = killer != null && !killer.equals(victim);
        boolean victimHasMace = victim.getUniqueId().equals(plugin.data.heavyHolder);
        boolean transfer = pk && victimHasMace && plugin.smpActive();

        // soulbound items never drop; they come back with you on respawn
        List<ItemStack> keep = e.getItemsToKeep();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack d = it.next();
            if (d == null || !plugin.items.isSoulbound(d)) continue;
            it.remove();
            if (plugin.items.weaponOf(d) == Weapon.HEAVY && transfer) continue; // goes to the killer instead
            keep.add(d);
        }
        // "if you die your weapon upgrade gets removed"
        plugin.weapons.resetProgress(victim);
        for (ItemStack k : keep) {
            if (plugin.items.isWeapon(k)) plugin.items.applyTier(k, 1);
        }
        plugin.abilities.forget(victim.getUniqueId());
        iceHits.remove(victim.getUniqueId());
        maceHits.remove(victim.getUniqueId());

        if (transfer) {
            // covers keepInventory too: giveHeavyHitter strips it from the old holder's inventory
            plugin.weapons.giveHeavyHitter(killer);
            Bukkit.broadcast(plugin.color("&8[&6Weapons&8] &e" + killer.getName() + "&7 took the &6Heavy Hitter&7 from &e" + victim.getName() + "&7!"));
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.showTitle(Title.title(Component.text("HEAVY HITTER", NamedTextColor.GOLD),
                        Component.text(killer.getName() + " claimed it", NamedTextColor.GRAY),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(500))));
            }
        }
        if (pk && plugin.smpActive()) plugin.weapons.addKill(killer);
        plugin.data.save();
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (e.getEntityType() != EntityType.WITHER || !plugin.smpActive()) return;
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        PlayerData pd = plugin.data.get(k.getUniqueId());
        if (pd.tier < 2) plugin.weapons.setTier(k, 2, true);
        else plugin.msg(k, "&7Wither down \u2014 your weapon is already at Tier II.");
    }

    // ------------------------------------------------------------------ damage rules

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        PlayerData pd = plugin.data.get(p.getUniqueId());
        boolean on = plugin.smpActive();
        switch (e.getCause()) {
            case FALL:
                if (plugin.abilities.noFallUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) {
                    e.setCancelled(true);
                } else if (on && pd.weapon == Weapon.BEAMER) {
                    e.setCancelled(true);
                }
                break;
            case FREEZE:
                if (on && pd.weapon == Weapon.ICE) e.setCancelled(true);
                break;
            default:
                break;
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (plugin.abilities.inAbilityDamage) return;
        Entity damager = e.getDamager();
        Player attacker = null;
        if (damager instanceof Player) attacker = (Player) damager;
        else if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile) damager).getShooter();
        }
        if (attacker == null) return;
        if (plugin.abilities.isDisabled(attacker)) {
            e.setCancelled(true);
            return;
        }
        if (!(damager instanceof Player) || !plugin.smpActive()) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity)) return;

        final LivingEntity victim = (LivingEntity) e.getEntity();
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        Weapon w = plugin.items.weaponOf(hand);
        if (w == null) return;
        PlayerData pd = plugin.data.get(attacker.getUniqueId());
        UUID id = attacker.getUniqueId();

        if (w == Weapon.ICE && pd.tier >= 2) {
            int n = iceHits.merge(id, 1, Integer::sum);
            if (n >= 6) {
                iceHits.put(id, 0);
                e.setDamage(e.getDamage() * 2.0);
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, false, true, true));
                Fx.sound(victim.getLocation(), "double_hit", "entity.player.attack.crit", 1.3f, 1f);
                victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getEyeLocation(), 25, 0.3, 0.4, 0.3, 0.1);
                victim.getWorld().spawnParticle(Particle.CRIT, victim.getEyeLocation(), 15, 0.3, 0.4, 0.3, 0.3);
                attacker.sendActionBar(plugin.color("&b\u2744 Double Hit!"));
            }
        } else if (w == Weapon.HEAVY) {
            final Player atk = attacker;
            Vector dir = victim.getLocation().toVector().subtract(atk.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 0.001) dir.normalize();
            final Vector push = dir.multiply(0.85).setY(0.28);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (victim.isValid()) victim.setVelocity(victim.getVelocity().add(push));
            });
            Fx.sound(victim.getLocation(), "colossus_hit", null, 1.0f, 1f);
            int n = maceHits.merge(id, 1, Integer::sum);
            if (n >= 3) {
                maceHits.put(id, 0);
                Bukkit.getScheduler().runTask(plugin, () -> colossusShockwave(atk, victim));
            }
        }
    }

    private void colossusShockwave(Player atk, LivingEntity centre) {
        if (!atk.isOnline() || !centre.isValid()) return;
        org.bukkit.Location c = centre.getLocation();
        Fx.sound(c, "colossus_shockwave", "entity.generic.explode", 1.3f, 1.2f);
        Material under = c.clone().subtract(0, 1, 0).getBlock().getType();
        if (under.isAir()) under = Material.STONE;
        c.getWorld().spawnParticle(Particle.BLOCK, c.clone().add(0, 0.2, 0), 40, 1.6, 0.2, 1.6, 0.05, under.createBlockData());
        Fx.ring(c.clone().add(0, 0.2, 0), 1.5, 16, Particle.CLOUD);
        Fx.ring(c.clone().add(0, 0.2, 0), 3.0, 28, Particle.CLOUD);
        for (LivingEntity t : plugin.abilities.targets(atk, c, 3.5)) {
            if (t.equals(centre)) continue;
            plugin.abilities.hurt(atk, t, 4.0);
            Vector away = t.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() > 0.001) away.normalize().multiply(0.7);
            away.setY(0.5);
            t.setVelocity(away);
        }
    }

    // ------------------------------------------------------------------ Tropical Bow Staff: kiwi fruit

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking() || !plugin.smpActive()) return;
        Player p = e.getPlayer();
        PlayerData pd = plugin.data.get(p.getUniqueId());
        if (pd.weapon != Weapon.TROPICAL) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.APPLE || plugin.items.isKiwi(hand)) return;

        int have = 0;
        for (ItemStack c : p.getInventory().getContents()) {
            if (plugin.items.isKiwi(c)) have += c.getAmount();
        }
        int room = 90 - have; // max 2 stacks of 45
        if (room <= 0) {
            p.sendActionBar(plugin.color("&cYou can only hold 2 stacks of Kiwi Fruits."));
            return;
        }
        int n = Math.min(Math.min(hand.getAmount(), room), 45);
        if (n >= hand.getAmount()) {
            p.getInventory().setItemInMainHand(plugin.items.createKiwi(n));
        } else {
            hand.setAmount(hand.getAmount() - n);
            plugin.weapons.give(p, plugin.items.createKiwi(n));
        }
        Fx.sound(p.getLocation(), "passive_chime", "block.amethyst_block.chime", 1f, 1.3f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 10, 0.4, 0.5, 0.4, 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!plugin.items.isKiwi(e.getItem())) return;
        Player p = e.getPlayer();
        p.setAbsorptionAmount(Math.min(20.0, p.getAbsorptionAmount() + 6.0)); // 3 hearts
        Fx.sound(p.getLocation(), "kiwi_eat", null, 1f, 1f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.2, 0), 12, 0.4, 0.4, 0.4, 0);
    }
}
