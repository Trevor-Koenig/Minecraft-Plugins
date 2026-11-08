// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * A rentable stall: a box of empty space players can build in while they rent it. The box is the
 * buildable space only; the stall's floor and walls are outside it, so they stay protected.
 * Its WorldGuard region (same box) decides who may build: the renter and their helpers.
 *
 * The world is kept by name (see SavedLocation for why): if it isn't loaded, the stall simply sits idle.
 */
public class Stall {

    private final String marketId;
    private final String id;
    private final String worldName;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private double rentPerDay;

    /** The renter, or null if the stall is free. */
    private UUID renter = null;
    /** Rent is paid up to this moment (epoch millis). */
    private long paidUntil = 0;
    private boolean autoRenew = true;
    /** Players the renter trusts to build too. */
    private final Set<UUID> helpers = new LinkedHashSet<>();
    /** True while rent is overdue: the renter and helpers are removed from the region, and shops are closed. */
    private boolean frozen = false;

    public Stall(String marketId, String id, String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, double rentPerDay) {
        this.marketId = marketId;
        this.id = id;
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.rentPerDay = rentPerDay;
    }

    public String getMarketId() { return marketId; }
    public String getId() { return id; }
    public String getWorldName() { return worldName; }
    /** The stall's world, or null if it isn't loaded. */
    public World getWorld() { return Bukkit.getWorld(worldName); }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    /** The WorldGuard region id. WorldGuard ids allow letters, digits and _ (among others), so this is always valid. */
    public String getRegionId() {
        return "thebox_stall_" + marketId + "_" + id;
    }

    public boolean contains(Location location) {
        return location.getWorld() != null && worldName.equals(location.getWorld().getName())
            && location.getBlockX() >= minX && location.getBlockX() <= maxX
            && location.getBlockY() >= minY && location.getBlockY() <= maxY
            && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    /** True if this stall's box and the given box share at least one block. */
    public boolean overlaps(String otherWorld, int oMinX, int oMinY, int oMinZ, int oMaxX, int oMaxY, int oMaxZ) {
        return worldName.equals(otherWorld)
            && minX <= oMaxX && oMinX <= maxX
            && minY <= oMaxY && oMinY <= maxY
            && minZ <= oMaxZ && oMinZ <= maxZ;
    }

    public double getRentPerDay() { return rentPerDay; }
    public void setRentPerDay(double rentPerDay) { this.rentPerDay = rentPerDay; }

    public UUID getRenter() { return renter; }
    public boolean isRented() { return renter != null; }
    public long getPaidUntil() { return paidUntil; }
    public void setPaidUntil(long paidUntil) { this.paidUntil = paidUntil; }
    public boolean isAutoRenew() { return autoRenew; }
    public void setAutoRenew(boolean autoRenew) { this.autoRenew = autoRenew; }
    public Set<UUID> getHelpers() { return helpers; }
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    /** Hands the stall to a new renter (or frees it, with null), resetting everything about the old rental. */
    public void setRenter(UUID renter, long paidUntil) {
        this.renter = renter;
        this.paidUntil = paidUntil;
        this.autoRenew = true;
        this.helpers.clear();
        this.frozen = false;
    }
}
