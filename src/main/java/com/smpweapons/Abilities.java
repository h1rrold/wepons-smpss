package com.smpweapons;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** All 15 active abilities (Tier I + Tier II of every weapon) plus the shared damage / root helpers. */
public final class Abilities {
    private final WeaponsPlugin plugin;

    /** True while one of our abilities is dealing damage, so combat listeners can ignore it. */
    public boolean inAbilityDamage = false;

    public final Map<UUID, Long> noAttackUntil = new HashMap<>();   // frozen / vine-trapped / spinning
    public final Map<UUID, Long> noInteractUntil = new HashMap<>(); // vine-trapped (can only eat)
    public final Map<UUID, Long> noFallUntil = new HashMap<>();     // Titan's Wrath / Cataclysm landings
    public final Set<UUID> spinning = new HashSet<>();
    public final Set<UUID> summons = new HashSet<>();               // Inferno blazes (never targeted)

    private final Map<UUID, Long> freezeImmune = new HashMap<>();
    private final Map<UUID, Burn> burning = new HashMap<>();
    private final Map<UUID, Coins> coins = new HashMap<>();
    private final List<Entity> temp = new ArrayList<>();

    private static final class Burn {
        UUID src;
        long end;
    }

    private static final class Coins {
        int coins = 5;
        int burst = 0;
        long rechargeEnd = 0;
        long oneBackAt = Long.MAX_VALUE;
        long lastThrow = 0;
    }

    public Abilities(WeaponsPlugin plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                tickBurning();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    public void shutdown() {
        for (Entity e : temp) if (e != null && e.isValid()) e.remove();
        temp.clear();
        for (UUID id : new ArrayList<>(summons)) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        summons.clear();
        for (UUID id : burning.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.setVisualFire(false);
        }
        burning.clear();
    }

    public void forget(UUID id) {
        noAttackUntil.remove(id);
        noInteractUntil.remove(id);
        noFallUntil.remove(id);
        spinning.remove(id);
        coins.remove(id);
    }

    // ===================================================================== entry point

    public boolean isDisabled(Player p) {
        return noAttackUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis();
    }

    public void use(Player p, Weapon w, int n) {
        if (!plugin.smpActive()) {
            bar(p, "&cThe SMP hasn't started yet.");
            return;
        }
        if (isDisabled(p)) return;
        PlayerData pd = plugin.data.get(p.getUniqueId());
        if (n == 2 && pd.tier < 2) {
            bar(p, "&cAbility 2 unlocks at Tier II &7(3 kills or kill a Wither)");
            Fx.sound(p.getLocation(), "error", "block.note_block.bass", 0.7f, 1f);
            return;
        }
        if (w == Weapon.DYNASTY && n == 1) {
            coinThrow(p, pd.tier);
            return;
        }
        if (w == Weapon.ICE && n == 2) {
            bar(p, "&bDouble Hit is automatic \u2014 keep swinging!");
            return;
        }
        String key = w.id + ":" + n;
        UUID id = p.getUniqueId();
        if (!plugin.cooldowns.ready(id, key)) {
            bar(p, "&c" + w.abilityName(n) + " ready in &e" + plugin.cooldowns.remainingSeconds(id, key) + "s");
            Fx.sound(p.getLocation(), "error", null, 0.5f, 1f);
            return;
        }
        boolean ok = false;
        switch (w) {
            case BEAMER:
                ok = n == 1 ? beam(p) : dash(p);
                break;
            case TROPICAL:
                ok = n == 1 ? vines(p) : bubble(p);
                break;
            case ICE:
                ok = deepFreeze(p);
                break;
            case DYNASTY:
                ok = spin(p);
                break;
            case INFERNO:
                ok = n == 1 ? lavaWall(p) : blazeCircle(p);
                break;
            case HELLSFIRE:
                ok = n == 1 ? slam(p) : cleave(p);
                break;
            case DRAGON:
                ok = n == 1 ? breath(p) : miniDragon(p);
                break;
            case HEAVY:
                ok = n == 1 ? cataclysm(p) : titan(p);
                break;
            default:
                break;
        }
        if (ok) {
            plugin.cooldowns.set(id, key, plugin.cooldownSeconds(w, n, pd.tier));
        }
    }

    // ===================================================================== helpers

    private void bar(Player p, String legacy) {
        p.sendActionBar(plugin.color(legacy));
    }

    public boolean isTrusted(Player owner, Entity other) {
        if (!(other instanceof Player)) return false;
        return plugin.data.get(owner.getUniqueId()).trusted.contains(other.getUniqueId());
    }

    public boolean validTarget(Player owner, Entity e) {
        if (e == null || e.equals(owner)) return false;
        if (!(e instanceof LivingEntity)) return false;
        if (e instanceof ArmorStand) return false;
        if (!e.isValid() || e.isDead()) return false;
        if (summons.contains(e.getUniqueId())) return false;
        if (e instanceof Player) {
            Player p = (Player) e;
            GameMode gm = p.getGameMode();
            if (gm == GameMode.SPECTATOR || gm == GameMode.CREATIVE) return false;
            return !isTrusted(owner, p);
        }
        return plugin.getConfig().getBoolean("affect-mobs", true);
    }

    /** Spherical target search around a point. */
    public List<LivingEntity> targets(Player owner, Location c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        if (c.getWorld() == null) return out;
        Vector cv = c.toVector();
        for (Entity e : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (!validTarget(owner, e)) continue;
            LivingEntity le = (LivingEntity) e;
            if (le.getBoundingBox().getCenter().distanceSquared(cv) <= r * r) out.add(le);
        }
        return out;
    }

    /** Cylinder target search (horizontal radius, +/- vertical). */
    public List<LivingEntity> targetsCyl(Player owner, Location c, double r, double vertical) {
        List<LivingEntity> out = new ArrayList<>();
        if (c.getWorld() == null) return out;
        for (Entity e : c.getWorld().getNearbyEntities(c, r, vertical, r)) {
            if (!validTarget(owner, e)) continue;
            Location l = e.getLocation();
            double dx = l.getX() - c.getX();
            double dz = l.getZ() - c.getZ();
            if (dx * dx + dz * dz <= r * r) out.add((LivingEntity) e);
        }
        return out;
    }

    public void hurt(Player src, LivingEntity t, double amount) {
        if (t.isDead()) return;
        boolean prev = inAbilityDamage;
        inAbilityDamage = true;
        try {
            t.setNoDamageTicks(0);
            t.damage(amount, src);
        } finally {
            inAbilityDamage = prev;
        }
    }

    /** Damage that ignores armour, resistance and absorption (still credits the kill to src). */
    public void trueHurt(Player src, LivingEntity t, double amount) {
        if (t.isDead()) return;
        boolean prev = inAbilityDamage;
        inAbilityDamage = true;
        try {
            t.setNoDamageTicks(0);
            double before = t.getHealth();
            double absBefore = t.getAbsorptionAmount();
            if (before - amount <= 0.5) {
                t.damage(1.0E6, src);
                return;
            }
            t.damage(0.01, src); // hurt flash, knockback and kill-credit bookkeeping
            if (t.getHealth() < before || t.getAbsorptionAmount() < absBefore) {
                t.setHealth(Math.max(0.1, before - amount));
            }
        } finally {
            inAbilityDamage = prev;
        }
    }

    /** Hellsfire-style hit: true damage + true fire that lasts 3x as long as vanilla fire (240 ticks). */
    public void fireHit(Player src, LivingEntity t, double amount) {
        trueHurt(src, t, amount);
        burn(src, t, 240);
    }

    public void burn(Player src, LivingEntity t, int ticks) {
        long end = System.currentTimeMillis() + ticks * 50L;
        Burn b = burning.get(t.getUniqueId());
        if (b == null) {
            b = new Burn();
            burning.put(t.getUniqueId(), b);
        }
        b.src = src.getUniqueId();
        b.end = Math.max(b.end, end);
        t.setVisualFire(true);
    }

    private void tickBurning() {
        if (burning.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(burning.keySet())) {
            Burn b = burning.get(id);
            Entity e = Bukkit.getEntity(id);
            if (b == null) continue;
            if (!(e instanceof LivingEntity) || e.isDead() || now > b.end || e.isInWater()) {
                if (e != null) e.setVisualFire(false);
                burning.remove(id);
                continue;
            }
            LivingEntity le = (LivingEntity) e;
            le.setVisualFire(true);
            Player src = Bukkit.getPlayer(b.src);
            if (src != null && src.isOnline()) trueHurt(src, le, 0.5);
            else le.damage(0.5);
        }
    }

    public void healOrAbsorb(Player p, double amount) {
        double max = p.getMaxHealth();
        double cur = p.getHealth();
        double heal = Math.min(Math.max(0, max - cur), amount);
        p.setHealth(Math.min(max, cur + heal));
        double extra = amount - heal;
        if (extra > 0) p.setAbsorptionAmount(Math.min(20.0, p.getAbsorptionAmount() + extra));
    }

    /** Immobilises an entity: potion effects + position lock (+ optional look lock for players). */
    private void root(LivingEntity t, int ticks, boolean lockLook) {
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 254, false, false, false));
        t.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, ticks, 200, false, false, false));
        final Mob mob = t instanceof Mob ? (Mob) t : null;
        final boolean hadAi = mob != null && mob.hasAI();
        if (mob != null) mob.setAI(false);
        final Location lock = t.getLocation().clone();
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!t.isValid() || tick >= ticks) {
                    if (mob != null && hadAi && mob.isValid()) mob.setAI(true);
                    cancel();
                    return;
                }
                if (t instanceof Player) {
                    Player pl = (Player) t;
                    if (lockLook) pl.setRotation(lock.getYaw(), lock.getPitch());
                    Location cur = pl.getLocation();
                    double dx = cur.getX() - lock.getX();
                    double dz = cur.getZ() - lock.getZ();
                    if (dx * dx + dz * dz > 0.25) {
                        Location back = cur.clone();
                        back.setX(lock.getX());
                        back.setZ(lock.getZ());
                        if (lockLook) {
                            back.setYaw(lock.getYaw());
                            back.setPitch(lock.getPitch());
                        }
                        pl.teleport(back);
                    }
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static Location pointOnSphere(Location c, double r) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double u = rnd.nextDouble() * Math.PI * 2;
        double v = Math.acos(2 * rnd.nextDouble() - 1);
        return c.clone().add(r * Math.sin(v) * Math.cos(u), r * Math.cos(v), r * Math.sin(v) * Math.sin(u));
    }

    private boolean grounded(Player p) {
        Location l = p.getLocation().clone().subtract(0, 0.15, 0);
        return !l.getBlock().isPassable() || p.isOnGround();
    }

    // ===================================================================== BEAMER SWORD

    /** Ability 1: Void Beam - 5 s beam, 1 heart/second to everything in the beam. */
    private boolean beam(Player p) {
        Fx.sound(p.getLocation(), "beam_charge", "block.beacon.activate", 1.6f, 1f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= 100) {
                    cancel();
                    return;
                }
                Location eye = p.getEyeLocation();
                Vector dir = eye.getDirection().normalize();
                Location origin = eye.clone().add(dir.clone().multiply(0.8)).add(0, -0.35, 0);
                double range = 20;
                RayTraceResult rt = p.getWorld().rayTraceBlocks(eye, dir, range, FluidCollisionMode.NEVER, true);
                double len = rt == null ? range : Math.max(1.0, rt.getHitPosition().distance(eye.toVector()));
                World w = p.getWorld();
                int i = 0;
                for (double d = 0; d <= len - 0.8; d += 0.45) {
                    Location pt = origin.clone().add(dir.clone().multiply(d));
                    w.spawnParticle(Particle.END_ROD, pt, 1, 0.03, 0.03, 0.03, 0);
                    if (i++ % 2 == 0) {
                        w.spawnParticle(Particle.DUST, pt, 1, 0.12, 0.12, 0.12, 0,
                                new Particle.DustOptions(Color.fromRGB(170, 70, 255), 1.4f));
                    }
                }
                if (rt != null) w.spawnParticle(Particle.ELECTRIC_SPARK, rt.getHitPosition().toLocation(w), 6, 0.2, 0.2, 0.2, 0.3);
                if (t % 20 == 0) {
                    Fx.sound(p.getLocation(), "beam_loop", "block.beacon.ambient", 1.3f, 1f);
                    for (Entity e : w.getNearbyEntities(eye, range, range, range)) {
                        if (!validTarget(p, e)) continue;
                        LivingEntity le = (LivingEntity) e;
                        Vector rel = le.getBoundingBox().getCenter().subtract(eye.toVector());
                        double proj = rel.dot(dir);
                        if (proj < 0 || proj > len) continue;
                        double dist = rel.clone().subtract(dir.clone().multiply(proj)).length();
                        if (dist <= 1.3) {
                            hurt(p, le, 2.0);
                            w.spawnParticle(Particle.ELECTRIC_SPARK, le.getBoundingBox().getCenter().toLocation(w), 12, 0.3, 0.4, 0.3, 0.2);
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** Ability 2: Rift Dash - blink 10 blocks forward. */
    private boolean dash(Player p) {
        Vector dir = p.getLocation().getDirection().clone();
        dir.setY(Math.max(-0.25, Math.min(0.35, dir.getY())));
        dir.normalize();
        final Vector step = dir;
        Fx.sound(p.getLocation(), "dash", "entity.ender_dragon.flap", 1.4f, 1.1f);
        p.setFallDistance(0);
        new BukkitRunnable() {
            int blocks = 0;
            Location cur = p.getLocation().clone();

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    cancel();
                    return;
                }
                for (int i = 0; i < 2 && blocks < 10; i++) {
                    Location next = cur.clone().add(step);
                    if (!next.getBlock().isPassable() || !next.clone().add(0, 1, 0).getBlock().isPassable()) {
                        blocks = 10;
                        break;
                    }
                    cur = next;
                    blocks++;
                    cur.getWorld().spawnParticle(Particle.PORTAL, cur.clone().add(0, 1, 0), 10, 0.25, 0.5, 0.25, 0.3);
                    cur.getWorld().spawnParticle(Particle.END_ROD, cur.clone().add(0, 1, 0), 2, 0.1, 0.3, 0.1, 0.02);
                }
                Location tp = cur.clone();
                tp.setYaw(p.getLocation().getYaw());
                tp.setPitch(p.getLocation().getPitch());
                p.teleport(tp);
                p.setFallDistance(0);
                if (blocks >= 10) {
                    p.setVelocity(step.clone().multiply(0.35));
                    Fx.sound(p.getLocation(), null, "entity.enderman.teleport", 1f, 1.2f);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ===================================================================== TROPICAL BOW STAFF

    /** Ability 1: Vine Snare - everyone nearby is rooted for 5 s, can't hit or look around, can only eat. */
    private boolean vines(Player p) {
        List<LivingEntity> ts = targets(p, p.getLocation(), 10);
        if (ts.isEmpty()) {
            bar(p, "&7No targets in range");
            return false;
        }
        Fx.sound(p.getLocation(), "vines", "block.vine.break", 1.6f, 1f);
        for (LivingEntity t : ts) trapVines(t, 100);
        return true;
    }

    private void trapVines(LivingEntity t, int ticks) {
        UUID id = t.getUniqueId();
        long end = System.currentTimeMillis() + ticks * 50L;
        noAttackUntil.merge(id, end, Long::max);
        if (t instanceof Player) noInteractUntil.merge(id, end, Long::max);
        root(t, ticks, true);
        if (t instanceof Player) bar((Player) t, "&2You are tangled in vines! You can only eat.");
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!t.isValid() || tick >= ticks) {
                    cancel();
                    return;
                }
                if (tick % 4 == 0) {
                    Location l = t.getLocation();
                    World w = l.getWorld();
                    w.spawnParticle(Particle.BLOCK, l.clone().add(0, 1, 0), 16, 0.45, 0.9, 0.45, 0, Material.VINE.createBlockData());
                    w.spawnParticle(Particle.HAPPY_VILLAGER, l.clone().add(0, 0.3, 0), 3, 0.4, 0.3, 0.4, 0);
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Ability 2: Verdant Bubble - 10 block bubble, drains 3 hearts from each opponent inside to you. */
    private boolean bubble(Player p) {
        final Location c = p.getLocation().clone().add(0, 1, 0);
        final double radius = 5.0;
        Fx.sound(c, "bubble", "entity.evoker.cast_spell", 1.6f, 1f);
        new BukkitRunnable() {
            int t = 0;
            final Set<UUID> drained = new HashSet<>();

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= 200) {
                    World w = c.getWorld();
                    for (int i = 0; i < 60; i++) {
                        w.spawnParticle(Particle.HAPPY_VILLAGER, pointOnSphere(c, radius), 1, 0, 0, 0, 0);
                    }
                    Fx.sound(c, null, "block.bubble_column.upwards_ambient", 1.4f, 1.4f);
                    cancel();
                    return;
                }
                World w = c.getWorld();
                for (int i = 0; i < 38; i++) {
                    w.spawnParticle(Particle.DUST, pointOnSphere(c, radius), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(70, 230, 110), 1.7f));
                }
                if (t % 5 == 0) {
                    for (LivingEntity e : targets(p, c, radius)) {
                        if (!drained.add(e.getUniqueId())) continue;
                        trueHurt(p, e, 6.0);
                        healOrAbsorb(p, 6.0);
                        Fx.sound(e.getLocation(), "bubble_drain", "entity.witch.drink", 1.2f, 1f);
                        Vector from = e.getBoundingBox().getCenter();
                        Vector to = p.getBoundingBox().getCenter();
                        Vector dir = to.clone().subtract(from);
                        double len = dir.length();
                        if (len > 0.1) {
                            dir.normalize();
                            for (double d = 0; d < len; d += 0.4) {
                                w.spawnParticle(Particle.HAPPY_VILLAGER, from.clone().add(dir.clone().multiply(d)).toLocation(w), 1, 0, 0, 0, 0);
                            }
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ===================================================================== ICE AXE

    /** Ability 1: Deep Freeze - 4 s frozen (1 heart / 2 s), then 30 s of chill + re-freeze immunity. */
    private boolean deepFreeze(Player p) {
        long now = System.currentTimeMillis();
        List<LivingEntity> ts = new ArrayList<>();
        for (LivingEntity e : targets(p, p.getLocation(), 8)) {
            Long imm = freezeImmune.get(e.getUniqueId());
            if (imm == null || imm < now) ts.add(e);
        }
        if (ts.isEmpty()) {
            bar(p, "&7No freezable targets in range");
            return false;
        }
        Fx.sound(p.getLocation(), "freeze", "block.glass.break", 1.8f, 0.9f);
        for (LivingEntity t : ts) freeze(t);
        return true;
    }

    private void freeze(LivingEntity t) {
        final UUID id = t.getUniqueId();
        long now = System.currentTimeMillis();
        freezeImmune.put(id, now + (80 + 600) * 50L);
        noAttackUntil.merge(id, now + 80 * 50L, Long::max);
        root(t, 80, false);
        final Player src = null;
        if (t instanceof Player) bar((Player) t, "&bYou are frozen solid!");
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (!t.isValid()) {
                    cancel();
                    return;
                }
                World w = t.getWorld();
                if (tick < 80) {
                    t.setFreezeTicks(t.getMaxFreezeTicks() - 1);
                    if (tick % 5 == 0) {
                        Location l = t.getLocation().add(0, 1, 0);
                        w.spawnParticle(Particle.SNOWFLAKE, l, 8, 0.4, 0.8, 0.4, 0.02);
                        w.spawnParticle(Particle.BLOCK, l, 10, 0.4, 0.8, 0.4, 0, Material.ICE.createBlockData());
                    }
                    if (tick == 20 || tick == 60) {
                        iceTick(t);
                    }
                } else if (tick == 80) {
                    t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 600, 0, false, false, false));
                    Fx.sound(t.getLocation(), "ice_crit", "block.glass.break", 1.2f, 1f);
                } else if (tick < 680) {
                    if (tick % 20 == 0) {
                        t.setFreezeTicks(Math.max(t.getFreezeTicks(), 80));
                        w.spawnParticle(Particle.SNOWFLAKE, t.getLocation().add(0, 1, 0), 4, 0.4, 0.8, 0.4, 0.01);
                    }
                } else {
                    cancel();
                    return;
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** 1 heart of "frost" damage, attributed to the most recent damager if any. */
    private void iceTick(LivingEntity t) {
        double before = t.getHealth();
        if (before - 2.0 <= 0.5) {
            t.setHealth(Math.max(0.1, before - 1.0));
        } else {
            t.setHealth(before - 2.0);
        }
        t.playEffect(org.bukkit.EntityEffect.HURT);
    }

    // ===================================================================== DYNASTY SWORD

    private Coins coinsOf(Player p) {
        return coins.computeIfAbsent(p.getUniqueId(), k -> new Coins());
    }

    private void refreshCoins(Coins c) {
        long now = System.currentTimeMillis();
        if (c.rechargeEnd != 0 && now >= c.rechargeEnd) {
            c.coins = 5;
            c.burst = 0;
            c.rechargeEnd = 0;
            c.oneBackAt = Long.MAX_VALUE;
        } else if (c.coins == 0 && c.rechargeEnd != 0 && now >= c.oneBackAt) {
            c.coins = 1;
            c.oneBackAt = Long.MAX_VALUE;
        }
    }

    public int coinsLeft(Player p) {
        Coins c = coinsOf(p);
        refreshCoins(c);
        return c.coins;
    }

    public int coinRechargeSeconds(Player p) {
        Coins c = coinsOf(p);
        refreshCoins(c);
        if (c.rechargeEnd == 0) return 0;
        return (int) Math.ceil(Math.max(0, c.rechargeEnd - System.currentTimeMillis()) / 1000.0);
    }

    /** Ability 1: Coin Barrage - 5 coins (2 hearts each), 1/s, 60 s to recharge all (10 s per coin thrown + 10 s). */
    private void coinThrow(Player p, int tier) {
        Coins c = coinsOf(p);
        refreshCoins(c);
        long now = System.currentTimeMillis();
        if (c.coins <= 0) {
            bar(p, "&6No coins \u2014 recharging in &e" + coinRechargeSeconds(p) + "s");
            Fx.sound(p.getLocation(), "error", null, 0.5f, 1f);
            return;
        }
        if (now - c.lastThrow < 1000) return;
        double mult = tier >= 2 ? plugin.getConfig().getDouble("tier2-cooldown-multiplier", 0.75) : 1.0;
        c.coins--;
        c.lastThrow = now;
        if (c.burst < 5) {
            c.burst++;
            c.rechargeEnd = now + (long) (Math.min(60, 10 + 10 * c.burst) * 1000L * mult);
            c.oneBackAt = now + (long) (10_000L * mult);
        } else {
            c.oneBackAt = Long.MAX_VALUE;
        }
        bar(p, "&6Coins: &e" + c.coins + "&6/5");
        throwCoin(p);
    }

    private void throwCoin(Player p) {
        Location eye = p.getEyeLocation();
        final Vector dir = eye.getDirection().normalize();
        Location start = eye.clone().add(dir.clone().multiply(0.9)).add(0, -0.3, 0);
        final World w = p.getWorld();
        final ItemStack coinItem = plugin.items.createCoin();
        final ItemDisplay disp = w.spawn(start, ItemDisplay.class, d -> {
            d.setItemStack(coinItem);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f(0, 0, 0, 1)));
        });
        temp.add(disp);
        Fx.sound(start, "coin_throw", "entity.arrow.shoot", 1.0f, 1.4f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!disp.isValid() || t > 26) {
                    disp.remove();
                    temp.remove(disp);
                    cancel();
                    return;
                }
                Location pos = disp.getLocation();
                Vector step = dir.clone().multiply(1.6);
                RayTraceResult rt = w.rayTraceBlocks(pos, dir, 1.6, FluidCollisionMode.NEVER, true);
                Location mid = pos.clone().add(step.clone().multiply(0.5));
                for (Entity e : w.getNearbyEntities(mid, 1.5, 1.5, 1.5)) {
                    if (!validTarget(p, e)) continue;
                    LivingEntity le = (LivingEntity) e;
                    hurt(p, le, 4.0);
                    Fx.sound(le.getLocation(), "coin_hit", "entity.experience_orb.pickup", 1.2f, 1f);
                    w.spawnParticle(Particle.DUST, le.getBoundingBox().getCenter().toLocation(w), 14, 0.3, 0.4, 0.3, 0,
                            new Particle.DustOptions(Color.fromRGB(255, 210, 60), 1.3f));
                    disp.remove();
                    temp.remove(disp);
                    cancel();
                    return;
                }
                if (rt != null) {
                    w.spawnParticle(Particle.DUST, rt.getHitPosition().toLocation(w), 8, 0.15, 0.15, 0.15, 0,
                            new Particle.DustOptions(Color.fromRGB(255, 210, 60), 1.2f));
                    disp.remove();
                    temp.remove(disp);
                    cancel();
                    return;
                }
                disp.teleport(pos.add(step));
                w.spawnParticle(Particle.DUST, pos, 1, 0.02, 0.02, 0.02, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 215, 70), 1.0f));
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Ability 2: Imperial Spin - 5 s spin. 2+ enemies get blasted away, a lone enemy is pulled in (and can hit you). */
    private boolean spin(Player p) {
        final UUID id = p.getUniqueId();
        if (spinning.contains(id)) return false;
        spinning.add(id);
        final Location lock = p.getLocation().clone();
        noAttackUntil.put(id, System.currentTimeMillis() + 100 * 50L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 100, 200, false, false, false));
        Fx.sound(lock, "spin", "item.trident.riptide_3", 1.6f, 1f);
        new BukkitRunnable() {
            int t = 0;
            float yaw = lock.getYaw();

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= 100) {
                    spinning.remove(id);
                    noAttackUntil.remove(id);
                    p.removePotionEffect(PotionEffectType.JUMP_BOOST);
                    cancel();
                    return;
                }
                yaw += 30f;
                p.setRotation(yaw, 0f);
                Location cur = p.getLocation();
                double dx = cur.getX() - lock.getX();
                double dz = cur.getZ() - lock.getZ();
                if (dx * dx + dz * dz > 0.04) {
                    Location back = cur.clone();
                    back.setX(lock.getX());
                    back.setZ(lock.getZ());
                    p.teleport(back);
                }
                World w = lock.getWorld();
                if (t % 2 == 0) {
                    for (int i = 0; i < 12; i++) {
                        double a = (yaw + i * 30) * Math.PI / 180.0;
                        w.spawnParticle(Particle.CRIT, cur.getX() + Math.cos(a) * 3, cur.getY() + 1, cur.getZ() + Math.sin(a) * 3, 1, 0, 0.05, 0, 0.1);
                    }
                    w.spawnParticle(Particle.SWEEP_ATTACK, cur.clone().add(Math.cos(Math.toRadians(yaw)) * 2.2, 1, Math.sin(Math.toRadians(yaw)) * 2.2), 1, 0, 0, 0, 0);
                }
                if (t % 20 == 0 && t > 0) Fx.sound(cur, "spin", "item.trident.riptide_2", 1.3f, 1.05f);
                if (t % 10 == 0) {
                    List<LivingEntity> opp = targetsCyl(p, cur, 5.0, 3.0);
                    if (opp.size() >= 2) {
                        for (LivingEntity e : opp) {
                            Vector away = e.getLocation().toVector().subtract(cur.toVector()).setY(0);
                            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                            away.normalize().multiply(1.3).setY(0.35);
                            e.setVelocity(away);
                        }
                    }
                } else if (t % 2 == 0) {
                    List<LivingEntity> opp = targetsCyl(p, cur, 5.0, 3.0);
                    if (opp.size() == 1) {
                        LivingEntity e = opp.get(0);
                        Vector pull = cur.toVector().subtract(e.getLocation().toVector()).setY(0);
                        if (pull.length() > 1.2) {
                            pull.normalize().multiply(0.45).setY(0.05);
                            e.setVelocity(pull);
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ===================================================================== INFERNO SWORD

    /** Ability 1: Lava Wall - ring 3 blocks out, 2 thick, 5 tall, 7 s, true fire damage inside it. */
    private boolean lavaWall(Player p) {
        Fx.sound(p.getLocation(), "lava_wall", "block.lava.pop", 1.8f, 0.9f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= 140) {
                    cancel();
                    return;
                }
                Location c = p.getLocation();
                World w = c.getWorld();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 28; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    double r = 3.0 + rnd.nextDouble() * 2.0;
                    double h = rnd.nextDouble() * 5.0;
                    Location pt = c.clone().add(Math.cos(a) * r, h, Math.sin(a) * r);
                    w.spawnParticle(i % 3 == 0 ? Particle.LAVA : Particle.FLAME, pt, 1, 0.05, 0.05, 0.05, 0.01);
                    if (i % 7 == 0) w.spawnParticle(Particle.DRIPPING_LAVA, pt, 1, 0, 0, 0, 0);
                }
                if (t % 10 == 0) {
                    for (Entity e : w.getNearbyEntities(c, 5.5, 5.5, 5.5)) {
                        if (!validTarget(p, e)) continue;
                        Location l = e.getLocation();
                        double dx = l.getX() - c.getX();
                        double dz = l.getZ() - c.getZ();
                        double d = Math.sqrt(dx * dx + dz * dz);
                        double dy = l.getY() - c.getY();
                        if (d >= 2.7 && d <= 5.3 && dy >= -1 && dy <= 5.2) {
                            LivingEntity le = (LivingEntity) e;
                            trueHurt(p, le, 1.5);
                            burn(p, le, 100);
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** Ability 2: Blaze Circle - 4 blazes, stand in the 3x3 for 3 s (it lasts 6 s) to be healed. */
    private boolean blazeCircle(Player p) {
        final Location center = p.getLocation().getBlock().getLocation().add(0.5, 0, 0.5);
        final World w = center.getWorld();
        final List<Blaze> blazes = new ArrayList<>();
        double[][] offs = {{2.3, 2.3}, {-2.3, 2.3}, {2.3, -2.3}, {-2.3, -2.3}};
        for (double[] o : offs) {
            Location l = center.clone().add(o[0], 0.9, o[1]);
            Blaze b = w.spawn(l, Blaze.class, bl -> {
                bl.setAI(false);
                bl.setInvulnerable(true);
                bl.setSilent(true);
                bl.setPersistent(false);
                bl.setCollidable(false);
            });
            summons.add(b.getUniqueId());
            blazes.add(b);
        }
        final double healHp = plugin.getConfig().getDouble("blaze-heal-hearts", 5) * 2.0;
        Fx.sound(center, "blaze_summon", "entity.blaze.ambient", 1.7f, 0.9f);
        new BukkitRunnable() {
            int t = 0;
            int inside = 0;
            boolean healed = false;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= 120) {
                    for (Blaze b : blazes) {
                        summons.remove(b.getUniqueId());
                        b.remove();
                    }
                    cancel();
                    return;
                }
                if (t % 2 == 0) {
                    for (double x = -1.5; x <= 1.5; x += 0.5) {
                        w.spawnParticle(Particle.FLAME, center.getX() + x, center.getY() + 0.15, center.getZ() + 1.5, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.FLAME, center.getX() + x, center.getY() + 0.15, center.getZ() - 1.5, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.FLAME, center.getX() + 1.5, center.getY() + 0.15, center.getZ() + x, 1, 0, 0, 0, 0);
                        w.spawnParticle(Particle.FLAME, center.getX() - 1.5, center.getY() + 0.15, center.getZ() + x, 1, 0, 0, 0, 0);
                    }
                }
                Location pl = p.getLocation();
                boolean in = Math.abs(pl.getX() - center.getX()) <= 1.5 && Math.abs(pl.getZ() - center.getZ()) <= 1.5
                        && Math.abs(pl.getY() - center.getY()) <= 2.5;
                inside = in ? inside + 1 : 0;
                if (in && !healed) {
                    if (t % 5 == 0) bar(p, "&6Healing\u2026 &e" + String.format("%.1f", inside / 20.0) + "&6/3s");
                    if (t % 3 == 0) {
                        for (Blaze b : blazes) {
                            Vector from = b.getLocation().add(0, 1, 0).toVector();
                            Vector to = p.getBoundingBox().getCenter();
                            Vector dir = to.clone().subtract(from);
                            double len = dir.length();
                            if (len < 0.1) continue;
                            dir.normalize();
                            for (double d = 0; d < len; d += 0.5) {
                                w.spawnParticle(Particle.FLAME, from.clone().add(dir.clone().multiply(d)).toLocation(w), 1, 0, 0, 0, 0);
                            }
                        }
                    }
                }
                if (in && !healed && inside >= 60) {
                    healed = true;
                    healOrAbsorb(p, healHp);
                    Fx.sound(p.getLocation(), "heal", "entity.player.levelup", 1.3f, 1f);
                    w.spawnParticle(Particle.HEART, p.getLocation().add(0, 1.4, 0), 12, 0.5, 0.5, 0.5, 0);
                    bar(p, "&aHealed!");
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    // ===================================================================== HELLSFIRE AXE

    /** Ability 1: Inferno Slam. */
    private boolean slam(Player p) {
        final Location c = p.getLocation().clone();
        final World w = c.getWorld();
        Fx.sound(c, "inferno_slam", "entity.generic.explode", 2.2f, 0.9f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
        for (LivingEntity e : targets(p, c, 6.0)) {
            fireHit(p, e, 6.0);
            e.setVelocity(e.getVelocity().add(new Vector(0, 0.6, 0)));
        }
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= 80) {
                    cancel();
                    return;
                }
                if (t < 8) {
                    double r = (t + 1) * 0.75;
                    Fx.ring(c.clone().add(0, 0.2, 0), r, (int) (r * 8) + 8, Particle.FLAME);
                    Fx.ring(c.clone().add(0, 0.4, 0), r, (int) (r * 4) + 6, Particle.LAVA);
                }
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 12; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    double r = Math.sqrt(rnd.nextDouble()) * 6.0;
                    Location pt = c.clone().add(Math.cos(a) * r, 0.15, Math.sin(a) * r);
                    w.spawnParticle(Particle.FLAME, pt, 1, 0.1, 0.05, 0.1, 0.01);
                    if (i % 4 == 0) w.spawnParticle(Particle.SMOKE, pt, 1, 0.1, 0.1, 0.1, 0.01);
                }
                if (t % 10 == 0 && t >= 10) {
                    for (LivingEntity e : targetsCyl(p, c, 6.0, 2.5)) burn(p, e, 100);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** Ability 2: Hellfire Cleave - a 12 block wave of fire with a burning trail. */
    private boolean cleave(Player p) {
        final World w = p.getWorld();
        Vector f = p.getLocation().getDirection().clone();
        f.setY(0);
        if (f.lengthSquared() < 0.01) f = new Vector(1, 0, 0);
        final Vector dir = f.normalize();
        final Vector perp = new Vector(-dir.getZ(), 0, dir.getX());
        final Location start = p.getLocation().clone().add(0, 0.2, 0).add(dir.clone().multiply(1.2));
        final List<Location> trail = new ArrayList<>();
        final Set<UUID> hit = new HashSet<>();
        Fx.sound(p.getLocation(), "cleave", "entity.blaze.shoot", 1.8f, 0.8f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= 8) {
                    cancel();
                    startTrail(p, trail);
                    return;
                }
                Location pos = start.clone().add(dir.clone().multiply(1.5 * t));
                // keep the wave glued to the ground height of the caster
                trail.add(pos.clone());
                for (double s = -3.0; s <= 3.0; s += 0.5) {
                    Location pt = pos.clone().add(perp.clone().multiply(s)).subtract(dir.clone().multiply(Math.abs(s) * 0.25));
                    w.spawnParticle(Particle.FLAME, pt.clone().add(0, 0.3, 0), 2, 0.1, 0.25, 0.1, 0.02);
                    w.spawnParticle(Particle.FLAME, pt.clone().add(0, 1.2, 0), 1, 0.1, 0.2, 0.1, 0.02);
                    if (((int) (s * 2)) % 2 == 0) w.spawnParticle(Particle.LAVA, pt.clone().add(0, 0.6, 0), 1, 0.1, 0.2, 0.1, 0);
                    if (((int) (s * 2)) % 4 == 0) w.spawnParticle(Particle.SMOKE, pt.clone().add(0, 1.6, 0), 1, 0.1, 0.1, 0.1, 0.01);
                }
                for (Entity e : w.getNearbyEntities(pos, 4, 3, 4)) {
                    if (!validTarget(p, e) || hit.contains(e.getUniqueId())) continue;
                    Vector rel = e.getLocation().toVector().subtract(pos.toVector());
                    double along = rel.dot(dir);
                    double side = rel.dot(perp);
                    if (Math.abs(along) <= 1.8 && Math.abs(side) <= 3.4) {
                        hit.add(e.getUniqueId());
                        LivingEntity le = (LivingEntity) e;
                        fireHit(p, le, 5.0);
                        le.setVelocity(dir.clone().multiply(1.3).setY(0.4));
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void startTrail(Player p, List<Location> trail) {
        if (trail.isEmpty()) return;
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= 60) {
                    cancel();
                    return;
                }
                for (Location l : trail) {
                    World w = l.getWorld();
                    for (double s = -2.0; s <= 2.0; s += 1.0) {
                        w.spawnParticle(Particle.FLAME, l.getX(), l.getY() + 0.15, l.getZ() + s, 1, 0.3, 0.05, 0.3, 0.005);
                    }
                }
                if (t % 10 == 0 && p.isOnline()) {
                    for (Location l : trail) {
                        for (Entity e : l.getWorld().getNearbyEntities(l, 2.2, 1.5, 2.2)) {
                            if (validTarget(p, e)) burn(p, (LivingEntity) e, 80);
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 5L, 1L);
    }

    // ===================================================================== DRAGON'S FANG

    /** Ability 1: Dragon's Breath - purple cloud around you for 5 s, double damage. */
    private boolean breath(Player p) {
        Fx.sound(p.getLocation(), "dragon_breath", "entity.ender_dragon.growl", 1.6f, 1f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || t >= 100) {
                    cancel();
                    return;
                }
                Location c = p.getLocation();
                World w = c.getWorld();
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < 22; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    double r = Math.sqrt(rnd.nextDouble()) * 4.0;
                    Location pt = c.clone().add(Math.cos(a) * r, 0.1 + rnd.nextDouble() * 0.5, Math.sin(a) * r);
                    w.spawnParticle(Particle.DUST, pt, 1, 0.1, 0.05, 0.1, 0,
                            new Particle.DustOptions(i % 2 == 0 ? Color.fromRGB(200, 60, 255) : Color.fromRGB(120, 0, 190), 1.7f));
                    if (i % 6 == 0) w.spawnParticle(Particle.WITCH, pt, 1, 0.1, 0.1, 0.1, 0);
                }
                if (t % 10 == 0) {
                    for (LivingEntity e : targetsCyl(p, c, 4.0, 2.5)) hurt(p, e, 2.0);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** Ability 2: Mini Dragon - swoops onto the target, 3 hearts to everyone near, heals you 4 hearts. */
    private boolean miniDragon(Player p) {
        final World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : w.getNearbyEntities(eye, 25, 25, 25)) {
            if (!validTarget(p, e)) continue;
            LivingEntity le = (LivingEntity) e;
            Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
            double d = to.length();
            if (d < 1.0 || to.clone().normalize().dot(look) < 0.5) continue;
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        final Location target;
        if (best != null) {
            target = best.getLocation().clone();
        } else {
            RayTraceResult rt = w.rayTraceBlocks(eye, look, 18, FluidCollisionMode.NEVER, true);
            Location t0 = rt != null ? rt.getHitPosition().toLocation(w) : eye.clone().add(look.clone().multiply(12));
            if (rt != null && rt.getHitBlockFace() == BlockFace.DOWN) t0.subtract(0, 0.5, 0);
            target = t0;
        }
        final Location start = p.getLocation().clone().add(0, 4, 0);
        launchDragon(p, start, target, () -> {
            Fx.sound(target, "dragon_slam", "entity.ender_dragon.hurt", 2.0f, 1.2f);
            w.spawnParticle(Particle.EXPLOSION, target, 3, 0.8, 0.4, 0.8, 0);
            w.spawnParticle(Particle.DUST, target.clone().add(0, 0.5, 0), 60, 1.5, 0.6, 1.5, 0,
                    new Particle.DustOptions(Color.fromRGB(180, 60, 255), 2.0f));
            boolean any = false;
            for (LivingEntity le : targets(p, target, 4.0)) {
                hurt(p, le, 6.0);
                any = true;
            }
            if (any) {
                healOrAbsorb(p, 8.0);
                w.spawnParticle(Particle.HEART, p.getLocation().add(0, 1.4, 0), 6, 0.4, 0.4, 0.4, 0);
            }
        });
        return true;
    }

    /** Admin helper (/weapon dragon): flies a harmless mini dragon 12 blocks ahead so you can check its facing. */
    public void testDragon(Player p) {
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        Location start = eye.clone().add(look.clone().multiply(2.0));
        Location target = eye.clone().add(look.clone().multiply(14.0));
        launchDragon(p, start, target, () -> Fx.sound(target, "dragon_slam", null, 1.0f, 1.2f));
    }

    /** Flies the 3D mini Ender Dragon (1 block big, flapping wings) from start to target, then runs onImpact. */
    private void launchDragon(Player p, Location start, Location target, Runnable onImpact) {
        final World w = start.getWorld();
        Vector dirv = target.toVector().subtract(start.toVector());
        double yawOff = plugin.getConfig().getDouble("mini-dragon-yaw-offset", 0.0);
        final float yaw = (float) (Math.toDegrees(Math.atan2(-dirv.getX(), dirv.getZ())) + yawOff);
        double horiz = Math.hypot(dirv.getX(), dirv.getZ());
        final float pitch = (float) -Math.toDegrees(Math.atan2(dirv.getY(), Math.max(0.001, horiz)));
        Location first = start.clone();
        first.setYaw(yaw);
        first.setPitch(pitch);
        final ItemDisplay disp = w.spawn(first, ItemDisplay.class, d -> {
            d.setItemStack(plugin.items.createMiniDragon(false));
            d.setPersistent(false);
            d.setTeleportDuration(2);
        });
        temp.add(disp);
        Fx.sound(start, "dragon_breath", "entity.ender_dragon.ambient", 1.8f, 1.3f);
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!disp.isValid() || !p.isOnline()) {
                    disp.remove();
                    temp.remove(disp);
                    cancel();
                    return;
                }
                i++;
                double s = Math.min(1.0, i / 14.0);
                double e = s * s;
                Location pos = start.clone().add(target.clone().subtract(start).toVector().multiply(e));
                pos.setYaw(yaw);
                pos.setPitch(pitch);
                disp.teleport(pos);
                if (i % 3 == 0) disp.setItemStack(plugin.items.createMiniDragon((i / 3) % 2 == 1));   // wing flap
                w.spawnParticle(Particle.DUST, pos, 5, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(Color.fromRGB(190, 70, 255), 1.4f));
                w.spawnParticle(Particle.PORTAL, pos, 5, 0.3, 0.3, 0.3, 0.4);
                if (i >= 14) {
                    onImpact.run();
                    disp.remove();
                    temp.remove(disp);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ===================================================================== HEAVY HITTER

    /** Ability 1: Cataclysm - pull everything in 8 blocks to you, then blast it away. */
    private boolean cataclysm(Player p) {
        final Location c = p.getLocation().clone();
        final World w = c.getWorld();
        noFallUntil.put(p.getUniqueId(), System.currentTimeMillis() + 5000);
        p.setVelocity(new Vector(0, 0.55, 0));
        Fx.sound(c, "cataclysm_charge", "block.beacon.activate", 1.8f, 0.8f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    cancel();
                    return;
                }
                if (t < 14) {
                    for (LivingEntity e : targets(p, c, 8.0)) {
                        Vector to = c.toVector().subtract(e.getLocation().toVector());
                        to.setY(0);
                        double d = to.length();
                        if (d > 1.2) {
                            to.normalize().multiply(Math.min(0.9, 0.12 * d + 0.2));
                            to.setY(0.05);
                            e.setVelocity(to);
                        }
                    }
                    double r = 8.0 * (1.0 - t / 14.0) + 0.5;
                    Fx.ring(c.clone().add(0, 0.3, 0), r, 36, Particle.REVERSE_PORTAL);
                    Fx.dustRing(c.clone().add(0, 0.3, 0), r, 24, 255, 190, 60, 1.4f);
                } else if (t == 14) {
                    Fx.sound(c, "cataclysm_blast", "entity.generic.explode", 2.6f, 0.7f);
                    w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 2, 0.5, 0.2, 0.5, 0);
                    for (LivingEntity e : targets(p, c, 8.5)) {
                        hurt(p, e, 7.0);
                        Vector away = e.getLocation().toVector().subtract(c.toVector()).setY(0);
                        if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                        away.normalize().multiply(1.6).setY(0.7);
                        e.setVelocity(away);
                    }
                } else if (t <= 24) {
                    double r = (t - 14) * 0.85;
                    Fx.ring(c.clone().add(0, 0.3, 0), r, (int) (r * 10) + 8, Particle.CLOUD);
                    Fx.ring(c.clone().add(0, 0.5, 0), r, (int) (r * 6) + 6, Particle.CRIT);
                } else {
                    cancel();
                    return;
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    /** Ability 2: Titan's Wrath - 2 s charge, leap to the target, crater smash. */
    private boolean titan(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 254, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 40, 200, false, false, false));
        Fx.sound(p.getLocation(), "titan_charge", "block.beacon.power_select", 1.8f, 1f);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    cancel();
                    return;
                }
                Location l = p.getLocation();
                World w = l.getWorld();
                double r = 3.0 * (1.0 - t / 40.0) + 0.4;
                for (int i = 0; i < 3; i++) {
                    double a = t * 0.5 + i * 2.094;
                    Location pt = l.clone().add(Math.cos(a) * r, 0.2 + t * 0.04, Math.sin(a) * r);
                    w.spawnParticle(Particle.ENCHANTED_HIT, pt, 1, 0, 0, 0, 0);
                    w.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 190, 50), 1.4f));
                }
                if (t % 10 == 0) bar(p, "&6Charging\u2026 &e" + (40 - t) / 20.0 + "s");
                if (t >= 39) {
                    cancel();
                    launchTitan(p);
                    return;
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void launchTitan(Player p) {
        final World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        RayTraceResult rt = w.rayTraceBlocks(eye, look, 28, FluidCollisionMode.NEVER, true);
        final Location target;
        if (rt != null) {
            Location t0 = rt.getHitPosition().toLocation(w);
            if (rt.getHitBlockFace() != null) t0.add(rt.getHitBlockFace().getDirection().multiply(0.6));
            target = t0;
        } else {
            target = eye.clone().add(look.clone().multiply(18));
        }
        final Location start = p.getLocation().clone();
        double dx = target.getX() - start.getX();
        double dz = target.getZ() - start.getZ();
        double horiz = Math.hypot(dx, dz);
        final int n = (int) Math.max(10, Math.min(26, horiz / 1.1));
        final double height = Math.min(9.0, 3.0 + horiz * 0.22);
        noFallUntil.put(p.getUniqueId(), System.currentTimeMillis() + 15000);
        Fx.sound(start, "dash", "entity.ender_dragon.flap", 1.6f, 0.7f);
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.isDead()) {
                    cancel();
                    return;
                }
                i++;
                double s = Math.min(1.0, (double) i / n);
                Vector pos = start.toVector().add(target.toVector().subtract(start.toVector()).multiply(s));
                pos.setY(pos.getY() + 4 * height * s * (1 - s));
                Vector vel = pos.subtract(p.getLocation().toVector());
                if (vel.length() > 2.5) vel.normalize().multiply(2.5);
                p.setVelocity(vel);
                w.spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.5, 0), 6, 0.3, 0.3, 0.3, 0.02);
                w.spawnParticle(Particle.CLOUD, p.getLocation(), 4, 0.3, 0.1, 0.3, 0.02);
                if (i >= n) {
                    cancel();
                    new BukkitRunnable() {
                        int waited = 0;

                        @Override
                        public void run() {
                            if (!p.isOnline() || p.isDead()) {
                                cancel();
                                return;
                            }
                            if ((waited > 1 && grounded(p)) || waited >= 30) {
                                cancel();
                                titanSmash(p);
                                return;
                            }
                            p.setVelocity(new Vector(0, -1.1, 0));
                            waited++;
                        }
                    }.runTaskTimer(plugin, 1L, 1L);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void titanSmash(Player p) {
        final Location c = p.getLocation().clone();
        final World w = c.getWorld();
        Fx.sound(c, "titan_slam", "entity.generic.explode", 2.6f, 0.8f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, c, 2, 1, 0.2, 1, 0);
        Material under = c.clone().subtract(0, 1, 0).getBlock().getType();
        if (under.isAir()) under = Material.STONE;
        final Material crater = under;
        for (LivingEntity e : targets(p, c, 5.0)) {
            hurt(p, e, 8.0);
            Vector away = e.getLocation().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() > 0.01) away.normalize().multiply(0.5);
            away.setY(1.2);
            e.setVelocity(away);
        }
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= 8) {
                    cancel();
                    return;
                }
                double r = (t + 1) * 0.65;
                int pts = (int) (r * 10) + 8;
                for (int i = 0; i < pts; i++) {
                    double a = Math.PI * 2 * i / pts;
                    Location pt = c.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r);
                    w.spawnParticle(Particle.BLOCK, pt, 3, 0.15, 0.25, 0.15, 0.05, crater.createBlockData());
                }
                Fx.ring(c.clone().add(0, 0.3, 0), r, pts, Particle.CLOUD);
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
