// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.regions;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A box of blocks, corners included. It has no world: whoever owns the box keeps the world, by name
 * (see SavedLocation for why).
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** The bounding box of a WorldEdit selection (any selection shape). */
    public static Box of(Region selection) {
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        return new Box(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    /** The box between two opposite corners, given in any order. */
    public static Box between(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** This box grown by n blocks on every side. */
    public Box expand(int n) {
        return new Box(minX - n, minY - n, minZ - n, maxX + n, maxY + n, maxZ + n);
    }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeY() { return maxY - minY + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }
    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** By block, ignoring the world (the caller checks it). A player standing on top of the box isn't in it. */
    public boolean contains(Location location) {
        return contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean overlaps(Box other) {
        return minX <= other.maxX && other.minX <= maxX
            && minY <= other.maxY && other.minY <= maxY
            && minZ <= other.maxZ && other.minZ <= maxZ;
    }

    public BlockVector3 min() { return BlockVector3.at(minX, minY, minZ); }
    public BlockVector3 max() { return BlockVector3.at(maxX, maxY, maxZ); }

    /** "10 64 -3 to 16 67 4": the two corners, for messages. */
    public String corners() {
        return minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ;
    }

    /** "7x4x8": size along x, y and z. */
    public String describe() {
        return sizeX() + "x" + sizeY() + "x" + sizeZ();
    }

    public void write(ConfigurationSection section, String path) {
        section.set(path + ".min.x", minX);
        section.set(path + ".min.y", minY);
        section.set(path + ".min.z", minZ);
        section.set(path + ".max.x", maxX);
        section.set(path + ".max.y", maxY);
        section.set(path + ".max.z", maxZ);
    }

    /** Reads a box written by {@link #write}, or returns null if there is none. */
    public static Box read(ConfigurationSection section, String path) {
        ConfigurationSection s = section.getConfigurationSection(path);
        if (s == null || !s.isConfigurationSection("min") || !s.isConfigurationSection("max")) {
            return null;
        }
        return new Box(s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"),
            s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"));
    }
}
