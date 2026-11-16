package com.smpweapons;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Passive perks, the cooldown HUD and the periodic housekeeping scan. */
public final class Passives implements Listener {
    private final WeaponsPlugin plugin;
    private final Map<UUID, Long> doubleJumpReadyAt = new HashMap<>();
    private final Set<UUID> flightGranted = new HashSet<>();
    private final Set<UUID> maceResist = new HashSet<>();
    private final Set<UUID> potionGuard = new HashSet<>();
    private boolean warned = false;

    /** Beneficial potion effects Dragon's Fang stretches to 10 minutes (healing-type effects are deliberately absent). */
    private static final Set<PotionEffectType> LONG_EFFECTS = new HashSet<>();

    static {
        LONG_EFFECTS.add(PotionEffectType.SPEED);
        LONG_EFFECTS.add(PotionEffectType.STRENGTH);
        LONG_EFFECTS.add(PotionEffectType.JUMP_BOOST);
        LONG_EFFECTS.add(PotionEffectType.FIRE_RESISTANCE);
        LONG_EFFECTS.add(PotionEffectType.NIGHT_VISION);
        LONG_EFFECTS.add(PotionEffectType.INVISIBILITY);
        LONG_EFFECTS.add(PotionEffectType.WATER_BREATHING);
        LONG_EFFECTS.add(PotionEffectType.SLOW_FALLING);
        LONG_EFFECTS.add(PotionEffectType.LUCK);
        LONG_EFFECTS.add(PotionEffectType.HASTE);
        LONG_EFFECTS.add(PotionEffectType.CONDUIT_POWER);
        LONG_EFFECTS.add(PotionEffectType.DOLPHINS_GRACE);
    }

    public Passives(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        // slow loop: effects, HUD, housekeeping (every 5 ticks)
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        slow(p, tick);
                    } catch (Exception ex) {
                        if (!warned) {
                            warned = true;
                            plugin.getLogger().warning("Passive tick error: " + ex);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 5L);

        // fast loop: powder snow walking + double jump flight toggling (every 2 ticks)
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        fast(p);
                    } catch (Exception ex) {
                        if (!warned) {
                            warned = true;
                            plugin.getLogger().warning("Passive tick error: " + ex);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 2L);
    }

    public void shutdown() {
        for (UUID id : new HashSet<>(flightGranted)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)) {
                p.setAllowFlight(false);
                p.setFlying(false);
            }
        }
        flightGranted.clear();
    }

    private boolean survivalLike(Player p) {
        return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
    }

    private void effect(Player p, PotionEffectType type, int ticks, int amp) {
        p.addPotionEffect(new PotionEffect(type, ticks, amp, true, false, false));
    }

    private static boolean snowBiome(Location l) {
        String b;
        try {
            b = l.getBlock().getBiome().getKey().getKey();
        } catch (Exception ex) {
            return false;
        }
        return b.contains("snowy") || b.contains("frozen") || b.equals("ice_spikes") || b.equals("grove");
    }

    // ------------------------------------------------------------------ slow loop

    private void slow(Player p, int tick) {
        UUID id = p.getUniqueId();
        if (!plugin.smpActive() || p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerData pd = plugin.data.get(id);
        Weapon w = pd.weapon;
        ItemStack hand = p.getInventory().getItemInMainHand();
        Weapon held = plugin.items.weaponOf(hand);

        // ~1 s: permanent-style effects
        if (tick % 4 == 0 && w != null) {
            switch (w) {
                case INFERNO:
                    effect(p, PotionEffectType.FIRE_RESISTANCE, 100, 0);
                    if (p.getWorld().getEnvironment() == World.Environment.NETHER) effect(p, PotionEffectType.RESISTANCE, 100, 0);
                    break;
                case HELLSFIRE:
                    effect(p, PotionEffectType.FIRE_RESISTANCE, 100, 0);
                    break;
                case DYNASTY:
                    int lvl = Math.max(1, Math.min(255, plugin.getConfig().getInt("hero-of-the-village-level", 10)));
                    effect(p, PotionEffectType.HERO_OF_THE_VILLAGE, 120, lvl - 1);
                    break;
                default:
                    break;
            }
        }
        // Ice axe: frost immunity + Speed III in snow biomes
        if (w == Weapon.ICE) {
            if (p.getFreezeTicks() > 0 && !plugin.abilities.isDisabled(p)) p.setFreezeTicks(0);
            if (tick % 2 == 0 && snowBiome(p.getLocation())) effect(p, PotionEffectType.SPEED, 30, 2);
        }
        // Colossus: Resistance I while holding the mace
        if (tick % 2 == 0) {
            if (held == Weapon.HEAVY) {
                effect(p, PotionEffectType.RESISTANCE, 25, 0);
                maceResist.add(id);
            } else if (maceResist.remove(id)) {
                PotionEffect cur = p.getPotionEffect(PotionEffectType.RESISTANCE);
                if (cur != null && cur.getAmplifier() == 0 && cur.getDuration() <= 30) p.removePotionEffect(PotionEffectType.RESISTANCE);
            }
        }
        // HUD every ~0.5 s
        if (tick % 2 == 0 && held != null) hud(p, held, pd);
        // housekeeping every ~2 s
        if (tick % 8 == (id.hashCode() & 7)) plugin.weapons.housekeeping(p);
    }

    private String status(Player p, Weapon w, int n, PlayerData pd) {
        String name = w.abilityName(n);
        if (w == Weapon.DYNASTY && n == 1) {
            int coins = plugin.abilities.coinsLeft(p);
            int rc = plugin.abilities.coinRechargeSeconds(p);
            return "&e" + name + ": &a" + coins + "/5" + (rc > 0 ? " &7(" + rc + "s)" : "");
        }
        if (w == Weapon.ICE && n == 2) {
            return pd.tier >= 2 ? "&e" + name + ": &aAUTO" : "&8" + name + ": &8Tier II";
        }
        if (n == 2 && pd.tier < 2) return "&8" + name + ": Tier II";
        int left = plugin.cooldowns.remainingSeconds(p.getUniqueId(), w.id + ":" + n);
        return "&e" + name + ": " + (left <= 0 ? "&aREADY" : "&c" + left + "s");
    }

    private void hud(Player p, Weapon w, PlayerData pd) {
        p.sendActionBar(plugin.color(status(p, w, 1, pd) + " &8| " + status(p, w, 2, pd)));
    }

    // ------------------------------------------------------------------ fast loop

    private void fast(Player p) {
        UUID id = p.getUniqueId();
        if (!plugin.smpActive() || p.getGameMode() == GameMode.SPECTATOR) {
            releaseFlight(p);
            return;
        }
        PlayerData pd = plugin.data.get(id);

        // Ice axe: walk on powder snow instead of falling through it
        if (pd.weapon == Weapon.ICE && !p.isSneaking()) {
            Location l = p.getLocation();
            Block feet = l.getBlock();
            if (feet.getType() == Material.POWDER_SNOW) {
                Vector v = p.getVelocity();
                if (v.getY() < 0.25) {
                    v.setY(0.28);
                    p.setVelocity(v);
                }
                p.setFreezeTicks(0);
            } else if (feet.getType().isAir() && l.clone().subtract(0, 0.2, 0).getBlock().getType() == Material.POWDER_SNOW) {
                Vector v = p.getVelocity();
                if (v.getY() < 0) {
                    v.setY(0);
                    p.setVelocity(v);
                }
            }
        }

        // Dynasty sword: double jump every 3 s, implemented with the creative-flight double-tap
        if (pd.weapon == Weapon.DYNASTY && survivalLike(p)) {
            boolean ready = doubleJumpReadyAt.getOrDefault(id, 0L) <= System.currentTimeMillis();
            if (ready && !p.getAllowFlight()) {
                p.setAllowFlight(true);
                flightGranted.add(id);
            } else if (!ready && flightGranted.contains(id) && p.getAllowFlight()) {
                p.setAllowFlight(false);
                p.setFlying(false);
            }
        } else {
            releaseFlight(p);
        }
    }

    private void releaseFlight(Player p) {
        if (flightGranted.remove(p.getUniqueId()) && survivalLike(p)) {
            p.setAllowFlight(false);
            p.setFlying(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFlight(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (!e.isFlying() || !flightGranted.contains(id) || !survivalLike(p)) return;
        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        doubleJumpReadyAt.put(id, System.currentTimeMillis() + 3000);
        Vector d = p.getLocation().getDirection().clone();
        d.setY(0);
        if (d.lengthSquared() > 0.001) d.normalize().multiply(0.45);
        else d = new Vector(0, 0, 0);
        d.setY(0.85);
        p.setVelocity(d);
        Fx.sound(p.getLocation(), "double_jump", "entity.phantom.flap", 1f, 1.2f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.05, 0.3, 0.05);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation(), 10, 0.3, 0.05, 0.3, 0,
                new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 210, 60), 1.2f));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        flightGranted.remove(e.getPlayer().getUniqueId());
        maceResist.remove(e.getPlayer().getUniqueId());
        doubleJumpReadyAt.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ Dragon's Fang: long potions

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player) || !plugin.smpActive()) return;
        Player p = (Player) e.getEntity();
        if (potionGuard.contains(p.getUniqueId())) return;
        if (plugin.data.get(p.getUniqueId()).weapon != Weapon.DRAGON) return;
        PotionEffect n = e.getNewEffect();
        if (n == null) return;
        EntityPotionEffectEvent.Cause c = e.getCause();
        if (c != EntityPotionEffectEvent.Cause.POTION_DRINK && c != EntityPotionEffectEvent.Cause.POTION_SPLASH
                && c != EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD) return;
        if (!LONG_EFFECTS.contains(n.getType()) || n.getDuration() >= 12000) return;
        e.setCancelled(true);
        PotionEffect longer = new PotionEffect(n.getType(), 12000, n.getAmplifier(), n.isAmbient(), n.hasParticles(), n.hasIcon());
        potionGuard.add(p.getUniqueId());
        try {
            p.addPotionEffect(longer);
        } finally {
            potionGuard.remove(p.getUniqueId());
        }
    }
}
