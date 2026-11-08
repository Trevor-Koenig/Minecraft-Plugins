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

    /** When the next reset is due (timed, or a triggered countdown). Kept in memory only. */
    private long nextResetMillis = 0;

    /** True while a percentage reset counts down. Kept in memory only. */
    private boolean resetTriggered = false;

    /** The last countdown warning sent (seconds left), so each is sent once. Kept in memory only. */
    private int lastWarning = -1;

    /** Name players see, in MiniMessage format (e.g. "<light_purple>Shulker Shell Shaft"), or null to show the id. */
    private String displayName = null;

    /** Reset once this percentage of the mine has been mined; 0 means off. */
    private int resetPercent = 0;

    /** Blocks broken inside the mine since its last reset. Kept in memory only. */
    private long blocksMined = 0;

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

    /** True if the location is inside the mine or within {@code margin} blocks of it. */
    public boolean isNear(Location location, int margin) {
        return world.equals(location.getWorld())
            && location.getBlockX() >= minX - margin && location.getBlockX() <= maxX + margin
            && location.getBlockY() >= minY - margin && location.getBlockY() <= maxY + margin
            && location.getBlockZ() >= minZ - margin && location.getBlockZ() <= maxZ + margin;
    }

    /** Where /mines sends players: the custom point if set, otherwise standing on the center of the mine's top. */
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

    /** True if a reset is coming: the mine resets on a timer, or a percentage reset is counting down. */
    public boolean isResetScheduled() {
        return resetIntervalSeconds > 0 || resetTriggered;
    }

    public boolean isResetDue(long nowMillis) {
        return isResetScheduled() && nowMillis >= nextResetMillis;
    }

    public long getNextResetMillis() { return nextResetMillis; }

    /** Called after each reset: starts the next timed interval and clears any countdown state. */
    public void scheduleNextReset() {
        nextResetMillis = System.currentTimeMillis() + resetIntervalSeconds * 1000L;
        resetTriggered = false;
        lastWarning = -1;
    }

    /** Starts a countdown to a reset in {@code seconds}, unless a timed reset is already due sooner. */
    public void triggerReset(int seconds) {
        long at = System.currentTimeMillis() + seconds * 1000L;
        if (!isResetScheduled() || at < nextResetMillis) {
            nextResetMillis = at;
        }
        resetTriggered = true;
    }

    public boolean isResetTriggered() { return resetTriggered; }

    public int getLastWarning() { return lastWarning; }
    public void setLastWarning(int secondsLeft) { this.lastWarning = secondsLeft; }

    /** The display name if one is set, otherwise the id. MiniMessage format. */
    public String getDisplayName() { return displayName != null ? displayName : id; }
    public boolean hasDisplayName() { return displayName != null; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public int getResetPercent() { return resetPercent; }
    public void setResetPercent(int percent) { this.resetPercent = percent; }

    public long getVolume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public void addBlockMined() { blocksMined++; }
    public void clearBlocksMined() { blocksMined = 0; }

    public int getPercentMined() {
        return (int) Math.min(100, blocksMined * 100 / getVolume());
    }

    /** True once enough of the mine has been broken to trigger a percentage reset. */
    public boolean isPercentResetDue() {
        return resetPercent > 0 && blocksMined * 100 >= getVolume() * resetPercent;
    }

    public long getSecondsUntilReset() {
        return Math.max(0, (nextResetMillis - System.currentTimeMillis()) / 1000);
    }
}
