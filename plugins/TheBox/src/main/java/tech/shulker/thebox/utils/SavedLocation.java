// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A location saved as plain values (world name and coordinates) instead of a Bukkit Location.
 *
 * Why: Bukkit can only load a Location from YAML if its world is already loaded; otherwise it throws
 * "unknown world", and the whole file fails to load. Worlds can load late (Multiverse loads its worlds
 * when it enables), so TheBox saves locations this way and turns them into Locations only when needed.
 */
public record SavedLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static SavedLocation of(Location location) {
        return new SavedLocation(location.getWorld().getName(),
            location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    /** As a Bukkit Location, or null if the world isn't loaded. */
    public Location toLocation() {
        World loaded = Bukkit.getWorld(world);
        return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
    }

    public void write(ConfigurationSection section, String path) {
        section.set(path + ".world", world);
        section.set(path + ".x", x);
        section.set(path + ".y", y);
        section.set(path + ".z", z);
        section.set(path + ".yaw", yaw);
        section.set(path + ".pitch", pitch);
    }

    /** Reads a location written by {@link #write}, or returns null if there is none. */
    public static SavedLocation read(ConfigurationSection section, String path) {
        ConfigurationSection s = section.getConfigurationSection(path);
        if (s == null || !s.isString("world")) {
            return null;
        }
        return new SavedLocation(s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
            (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }
}
