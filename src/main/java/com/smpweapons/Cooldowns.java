package com.smpweapons;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Cooldowns {
    private final Map<UUID, Map<String, Long>> map = new HashMap<>();

    public void set(UUID id, String key, double seconds) {
        if (seconds <= 0) return;
        map.computeIfAbsent(id, k -> new HashMap<>()).put(key, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    public long remainingMs(UUID id, String key) {
        Map<String, Long> m = map.get(id);
        if (m == null) return 0;
        Long end = m.get(key);
        if (end == null) return 0;
        long left = end - System.currentTimeMillis();
        return Math.max(0, left);
    }

    public boolean ready(UUID id, String key) {
        return remainingMs(id, key) <= 0;
    }

    public int remainingSeconds(UUID id, String key) {
        return (int) Math.ceil(remainingMs(id, key) / 1000.0);
    }

    public void clear(UUID id) {
        map.remove(id);
    }
}
