package com.smpweapons;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;

/** Tiny helpers for layered sounds and particle shapes. */
public final class Fx {
    private Fx() {
    }

    /** Plays the resource-pack sound plus a quieter vanilla layer (so it still sounds fine without the pack). */
    public static void sound(Location l, String custom, String vanilla, float vol, float pitch) {
        World w = l.getWorld();
        if (w == null) return;
        if (custom != null) w.playSound(l, "weapons:" + custom, SoundCategory.PLAYERS, vol, pitch);
        if (vanilla != null) w.playSound(l, vanilla, SoundCategory.PLAYERS, vol * 0.55f, pitch);
    }

    public static void dust(Location l, int count, double spread, int r, int g, int b, float size) {
        World w = l.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.DUST, l, count, spread, spread, spread, 0, new Particle.DustOptions(Color.fromRGB(r, g, b), size));
    }

    /** Horizontal particle ring. */
    public static void ring(Location c, double radius, int points, Particle p) {
        World w = c.getWorld();
        if (w == null) return;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            w.spawnParticle(p, c.getX() + Math.cos(a) * radius, c.getY(), c.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0);
        }
    }

    public static void dustRing(Location c, double radius, int points, int r, int g, int b, float size) {
        World w = c.getWorld();
        if (w == null) return;
        Particle.DustOptions opt = new Particle.DustOptions(Color.fromRGB(r, g, b), size);
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            w.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * radius, c.getY(), c.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0, opt);
        }
    }
}
