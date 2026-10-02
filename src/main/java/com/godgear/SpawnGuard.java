package com.godgear;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple config-defined "no god abilities here" zones (spheres), e.g. server spawn.
 * No WorldGuard dependency: define zones directly in config.yml under spawn-zones.
 */
public final class SpawnGuard {

    private record Zone(String world, double x, double y, double z, double radiusSq) {
        boolean contains(Location l) {
            if (!world.equalsIgnoreCase(l.getWorld().getName())) return false;
            double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
            return dx * dx + dy * dy + dz * dz <= radiusSq;
        }
    }

    private static List<Zone> zones = List.of();

    private SpawnGuard() {}

    public static void load(Plugin plugin) {
        List<Zone> loaded = new ArrayList<>();
        List<?> raw = plugin.getConfig().getList("spawn-zones");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof ConfigurationSection cs) {
                    loaded.add(new Zone(cs.getString("world", "world"), cs.getDouble("x"), cs.getDouble("y"),
                            cs.getDouble("z"), Math.pow(cs.getDouble("radius", 50), 2)));
                } else if (o instanceof java.util.Map<?, ?> m) {
                    Object worldObj = m.get("world");
                    loaded.add(new Zone(worldObj != null ? String.valueOf(worldObj) : "world",
                            num(m.get("x")), num(m.get("y")), num(m.get("z")),
                            Math.pow(m.containsKey("radius") ? num(m.get("radius")) : 50, 2)));
                }
            }
        }
        zones = loaded;
        plugin.getLogger().info("GodGear: loaded " + zones.size() + " spawn zone(s).");
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    public static boolean inSpawn(Location l) {
        if (zones.isEmpty() || l.getWorld() == null) return false;
        for (Zone z : zones) {
            if (z.contains(l)) return true;
        }
        return false;
    }

    public static boolean inSpawn(Player p) {
        return inSpawn(p.getLocation());
    }
}
