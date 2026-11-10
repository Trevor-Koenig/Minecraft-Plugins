// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.regions;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.World;

/**
 * The WorldGuard regions TheBox owns (market stalls, cells). TheBox keeps each one matching its own data:
 * non-members can't build in a region, so its member list is what decides who can build.
 */
public final class Regions {

    private Regions() {
    }

    /** The world's region manager, or null if the world isn't loaded (or WorldGuard has no regions for it). */
    public static RegionManager manager(World world) {
        return world == null ? null
            : WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    /**
     * Makes a cuboid region match: creates it if it's missing (say, someone deleted it with /rg remove), moves it if
     * its corners changed (keeping its flags and priority), and sets its members to exactly these players.
     * {@code configure}, if given, runs on the region every time (for flags TheBox wants on it). Does nothing if the
     * world isn't loaded; it runs again on the next change or reload.
     */
    public static void sync(Logger logger, World world, String id, Box box, int priority, Collection<UUID> members,
                            Consumer<ProtectedRegion> configure) {
        RegionManager manager = manager(world);
        if (manager == null) {
            return;
        }
        ProtectedRegion region = manager.getRegion(id);
        if (region == null || !region.getMinimumPoint().equals(box.min()) || !region.getMaximumPoint().equals(box.max())) {
            // A region's corners can't be changed, so a moved region is a new region with the old one's settings.
            // addRegion replaces the region with the same id.
            ProtectedRegion replacement = new ProtectedCuboidRegion(id, box.min(), box.max());
            if (region != null) {
                replacement.copyFrom(region);
            } else {
                replacement.setPriority(priority);
            }
            manager.addRegion(replacement);
            region = replacement;
        }
        DefaultDomain domain = region.getMembers();
        domain.removeAll();
        members.forEach(domain::addPlayer);
        if (configure != null) {
            configure.accept(region);
        }
        save(logger, manager);
    }

    public static void remove(Logger logger, World world, String id) {
        RegionManager manager = manager(world);
        if (manager != null && manager.hasRegion(id)) {
            manager.removeRegion(id);
            save(logger, manager);
        }
    }

    private static void save(Logger logger, RegionManager manager) {
        try {
            manager.saveChanges();
        } catch (StorageException e) {
            logger.severe("Could not save WorldGuard regions: " + e.getMessage());
        }
    }
}
