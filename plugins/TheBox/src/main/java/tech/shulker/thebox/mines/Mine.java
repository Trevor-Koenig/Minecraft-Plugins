// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.mines;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockType;

/** A cuboid region that refills with a weighted mix of blocks. */
public class Mine {

    private final String id;
    private final World world;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    /** Block type to weight, e.g. stone 70, coal ore 20, iron ore 10. Weights are relative. */
    private final Map<BlockType, Integer> blocks = new LinkedHashMap<>();

    /** Seconds between automatic resets; 0 means no automatic reset. */
    private int resetIntervalSeconds = 0;

    /** Custom teleport point, or null to use the center of the mine's top. */
    private Location customTeleport = null;

    /** When the next automatic reset is due. Kept in memory only. */
    private long nextResetMillis = 0;

    public Mine(String id, World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.id = id;
        this.world = world;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public String getId() { return id; }
    public World getWorld() { return world; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public Map<BlockType, Integer> getBlocks() { return blocks; }

    /** The permission a player needs to teleport to this mine. */
    public String getPermission() {
        return "thebox.mine." + id;
    }

    /** True if the location is inside this mine's region. */
    public boolean contains(Location location) {
        return world.equals(location.getWorld())
            && location.getBlockX() >= minX && location.getBlockX() <= maxX
            && location.getBlockY() >= minY && location.getBlockY() <= maxY
            && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    /** Where players are sent: the custom point if set, otherwise standing on the center of the mine's top. */
    public Location getTeleport() {
        if (customTeleport != null) {
            return customTeleport;
        }
        double centerX = (minX + maxX + 1) / 2.0;
        double centerZ = (minZ + maxZ + 1) / 2.0;
        return new Location(world, centerX, maxY + 1, centerZ);
    }

    public Location getCustomTeleport() { return customTeleport; }
    public void setCustomTeleport(Location location) { this.customTeleport = location; }

    public int getResetIntervalSeconds() { return resetIntervalSeconds; }

    public void setResetIntervalSeconds(int seconds) {
        this.resetIntervalSeconds = seconds;
        scheduleNextReset();
    }

    public boolean isResetDue(long nowMillis) {
        return resetIntervalSeconds > 0 && nowMillis >= nextResetMillis;
    }

    public void scheduleNextReset() {
        nextResetMillis = System.currentTimeMillis() + resetIntervalSeconds * 1000L;
    }

    public long getSecondsUntilReset() {
        return Math.max(0, (nextResetMillis - System.currentTimeMillis()) / 1000);
    }
}
