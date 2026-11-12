// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;
import tech.shulker.thebox.regions.Box;

/**
 * A player's cell: a solid plot that TheBox carves rooms out of, and a front space in front of it.
 *
 * The plot is the solid space the tier's largest room needs, plus the front wall: the plot's layer touching the
 * front space. Rooms are carved behind the front wall, centered side to side, starting at the plot's bottom layer,
 * and grow away from the front, so the front wall (and any door in it) never moves.
 *
 * Two WorldGuard regions decide who can build: the room (at its current size), and the front (the front space plus
 * the front wall behind it). The plot that isn't carved yet is in neither, so nobody can dig themselves a bigger cell.
 *
 * The world is kept by name (see SavedLocation for why): if it isn't loaded, the cell simply sits idle.
 */
public class Cell {

    /** Which side of the plot the front space is on. Rooms grow the opposite way. */
    public enum Front {
        NORTH, SOUTH, WEST, EAST;

        /** Turns a block that sat in a north-facing front to sit the same way in this one (tier fronts are stored facing north). */
        StructureRotation fromNorth() {
            return switch (this) {
                case NORTH -> StructureRotation.NONE;
                case EAST -> StructureRotation.CLOCKWISE_90;
                case SOUTH -> StructureRotation.CLOCKWISE_180;
                case WEST -> StructureRotation.COUNTERCLOCKWISE_90;
            };
        }

        /** The opposite of {@link #fromNorth()}. */
        StructureRotation toNorth() {
            return switch (this) {
                case NORTH -> StructureRotation.NONE;
                case EAST -> StructureRotation.COUNTERCLOCKWISE_90;
                case SOUTH -> StructureRotation.CLOCKWISE_180;
                case WEST -> StructureRotation.CLOCKWISE_90;
            };
        }

        /** Facing into the room. Bukkit yaw: 0 is south, 90 west, 180 north, -90 east. */
        float yawIntoRoom() {
            return switch (this) {
                case NORTH -> 0;    // the room is south of a north front
                case SOUTH -> 180;
                case WEST -> -90;   // the room is east of a west front
                case EAST -> 90;
            };
        }

        /**
         * The side of the plot a front space is on, or null if it isn't lying against one side (not above or below),
         * within the plot's width and height.
         */
        static Front of(Box plot, Box front) {
            boolean withinX = front.minX() >= plot.minX() && front.maxX() <= plot.maxX();
            boolean withinY = front.minY() >= plot.minY() && front.maxY() <= plot.maxY();
            boolean withinZ = front.minZ() >= plot.minZ() && front.maxZ() <= plot.maxZ();
            if (!withinY) {
                return null;
            }
            if (withinX && front.maxZ() == plot.minZ() - 1) {
                return NORTH;
            }
            if (withinX && front.minZ() == plot.maxZ() + 1) {
                return SOUTH;
            }
            if (withinZ && front.maxX() == plot.minX() - 1) {
                return WEST;
            }
            if (withinZ && front.minX() == plot.maxX() + 1) {
                return EAST;
            }
            return null;
        }
    }

    private final String id;
    private final int tier;
    private final String worldName;
    private final Box plot;
    /** What the plot is built of (e.g. "minecraft:stone_bricks"), so a freed cell can be rebuilt the same. */
    private final String wallBlock;

    /** The front space, or null until an admin sets it. A cell without one isn't handed out. */
    private Box front = null;
    private Front facing = null;

    /** The owner, or null if the cell is free. */
    private UUID owner = null;
    /** Index into the tier's sizes. */
    private int size = 0;
    /** Players the owner trusts to build too. */
    private final Set<UUID> helpers = new LinkedHashSet<>();
    /** While the owner is moving out to a bigger cell: when this one is cleared (epoch millis). 0 otherwise. */
    private long releaseAt = 0;

    public Cell(String id, int tier, String worldName, Box plot, String wallBlock) {
        this.id = id;
        this.tier = tier;
        this.worldName = worldName;
        this.plot = plot;
        this.wallBlock = wallBlock;
    }

    public String getId() { return id; }
    public int getTier() { return tier; }
    public String getWorldName() { return worldName; }
    /** The cell's world, or null if it isn't loaded. */
    public World getWorld() { return Bukkit.getWorld(worldName); }
    public Box getPlot() { return plot; }
    public String getWallBlock() { return wallBlock; }
    public Box getFront() { return front; }
    public Front getFacing() { return facing; }
    public UUID getOwner() { return owner; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public Set<UUID> getHelpers() { return helpers; }
    public long getReleaseAt() { return releaseAt; }
    public void setReleaseAt(long releaseAt) { this.releaseAt = releaseAt; }

    /** Set up (it has a front space), so it can be handed out. */
    public boolean isReady() { return front != null; }
    public boolean isFree() { return owner == null; }
    /** True while its owner is moving to a bigger cell; this one is cleared at {@link #getReleaseAt()}. */
    public boolean isMovingOut() { return releaseAt > 0; }

    public void setFront(Box front, Front facing) {
        this.front = front;
        this.facing = facing;
    }

    /** Hands the cell to a new owner (or frees it, with null) at its starting size. */
    public void assign(UUID owner) {
        this.owner = owner;
        this.size = 0;
        this.helpers.clear();
        this.releaseAt = 0;
    }

    public String getRoomRegionId() { return "thebox_cell_" + id; }
    public String getFrontRegionId() { return "thebox_cell_" + id + "_front"; }

    /** The plot's size side to side, seen from the front. */
    public static int plotWidth(Box plot, Front facing) {
        return facing == Front.NORTH || facing == Front.SOUTH ? plot.sizeX() : plot.sizeZ();
    }

    /** The plot's size front to back, front wall included. */
    public static int plotDepth(Box plot, Front facing) {
        return facing == Front.NORTH || facing == Front.SOUTH ? plot.sizeZ() : plot.sizeX();
    }

    /** The room at a size: centered side to side, right behind the front wall, from the plot's bottom layer up. */
    public Box room(CellTier.Size size) {
        int w = size.width();
        int d = size.depth();
        int y1 = plot.minY();
        int y2 = plot.minY() + size.height() - 1;
        return switch (facing) {
            case NORTH, SOUTH -> {
                int x1 = plot.minX() + (plot.sizeX() - w) / 2;
                int z1 = facing == Front.NORTH ? plot.minZ() + 1 : plot.maxZ() - d;
                yield new Box(x1, y1, z1, x1 + w - 1, y2, z1 + d - 1);
            }
            case WEST, EAST -> {
                int z1 = plot.minZ() + (plot.sizeZ() - w) / 2;
                int x1 = facing == Front.WEST ? plot.minX() + 1 : plot.maxX() - d;
                yield new Box(x1, y1, z1, x1 + d - 1, y2, z1 + w - 1);
            }
        };
    }

    /**
     * A block of the cell's front, by its place as you face the cell from the corridor: {@code side} from the plot's
     * right edge, {@code up} from its bottom layer, {@code out} from the front wall (0) into the front space (1, 2, ...)
     * or into the room (-1). The four directions are the same layout turned (not mirrored), so a front saved from one
     * cell fits cells facing any way.
     */
    public Block frontBlock(World world, int side, int up, int out) {
        int y = plot.minY() + up;
        return switch (facing) {
            case NORTH -> world.getBlockAt(plot.minX() + side, y, plot.minZ() - out);
            case EAST -> world.getBlockAt(plot.maxX() + out, y, plot.minZ() + side);
            case SOUTH -> world.getBlockAt(plot.maxX() - side, y, plot.maxZ() + out);
            case WEST -> world.getBlockAt(plot.minX() - out, y, plot.maxZ() - side);
        };
    }

    /** Where a room starts side to side, counted like {@link #frontBlock}'s {@code side}. */
    public int roomSide(CellTier.Size size) {
        Box room = room(size);
        return switch (facing) {
            case NORTH -> room.minX() - plot.minX();
            case EAST -> room.minZ() - plot.minZ();
            case SOUTH -> plot.maxX() - room.maxX();
            case WEST -> plot.maxZ() - room.maxZ();
        };
    }

    /** How deep the front space is, from the front wall out. */
    public int frontDepth() {
        return facing == Front.NORTH || facing == Front.SOUTH ? front.sizeZ() : front.sizeX();
    }

    /** The front space plus the front wall right behind it: where the owner can build at the front. */
    public Box frontRegion() {
        return switch (facing) {
            case NORTH -> new Box(front.minX(), front.minY(), front.minZ(), front.maxX(), front.maxY(), front.maxZ() + 1);
            case SOUTH -> new Box(front.minX(), front.minY(), front.minZ() - 1, front.maxX(), front.maxY(), front.maxZ());
            case WEST -> new Box(front.minX(), front.minY(), front.minZ(), front.maxX() + 1, front.maxY(), front.maxZ());
            case EAST -> new Box(front.minX() - 1, front.minY(), front.minZ(), front.maxX(), front.maxY(), front.maxZ());
        };
    }

    /** Inside the room, just behind the front wall, facing in. Null if the world isn't loaded. */
    public Location inside(CellTier.Size size) {
        World world = getWorld();
        if (world == null) {
            return null;
        }
        Box room = room(size);
        double middleX = (room.minX() + room.maxX() + 1) / 2.0;
        double middleZ = (room.minZ() + room.maxZ() + 1) / 2.0;
        double x;
        double z;
        switch (facing) {
            case NORTH -> { x = middleX; z = room.minZ() + 0.5; }
            case SOUTH -> { x = middleX; z = room.maxZ() + 0.5; }
            case WEST -> { x = room.minX() + 0.5; z = middleZ; }
            default -> { x = room.maxX() + 0.5; z = middleZ; }
        }
        return new Location(world, x, room.minY(), z, facing.yawIntoRoom(), 0);
    }

    /** Where visitors arrive: the middle of the front space, facing the cell. Null if the world isn't loaded. */
    public Location visit() {
        World world = getWorld();
        if (world == null || front == null) {
            return null;
        }
        return new Location(world, (front.minX() + front.maxX() + 1) / 2.0, front.minY(),
            (front.minZ() + front.maxZ() + 1) / 2.0, facing.yawIntoRoom(), 0);
    }

    /**
     * Like visit, but somewhere a player fits: the owner can build in the front space, so if its middle is blocked,
     * the first free spot on its floor is used. Null if the world isn't loaded or the whole floor is built over.
     */
    public Location standingSpot() {
        Location middle = visit();
        if (middle == null || fits(middle)) {
            return middle;
        }
        for (int x = front.minX(); x <= front.maxX(); x++) {
            for (int z = front.minZ(); z <= front.maxZ(); z++) {
                Location spot = new Location(middle.getWorld(), x + 0.5, front.minY(), z + 0.5, middle.getYaw(), 0);
                if (fits(spot)) {
                    return spot;
                }
            }
        }
        return null;
    }

    /** True if a player standing here has room for their feet and head. */
    private static boolean fits(Location location) {
        Block feet = location.getBlock();
        return feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable();
    }
}
