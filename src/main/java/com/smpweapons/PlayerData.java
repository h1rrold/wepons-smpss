package com.smpweapons;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Everything we persist about one player. */
public final class PlayerData {
    public Weapon weapon;          // their random weapon (never HEAVY)
    public int tier = 1;           // 1 or 2
    public int kills = 0;          // player kills since last death / reroll-keep
    public String lastName;
    public final Set<UUID> trusted = new LinkedHashSet<>();
}
