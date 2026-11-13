// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockType;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.market.Impound;
import tech.shulker.thebox.market.ShopHook;
import tech.shulker.thebox.regions.Box;
import tech.shulker.thebox.regions.RegionClearer;
import tech.shulker.thebox.regions.Regions;
import tech.shulker.thebox.utils.Durations;

/**
 * Owns the cells: storage in cells.yml (tiers and cells), carving rooms, each cell's WorldGuard regions, handing
 * out cells, upgrades, tier moves, and the timer that clears old cells after a move.
 */
public class CellManager implements Listener {

    /** Like stall regions: cell regions outrank a surrounding region (say, one protecting the whole cell block). */
    private static final int REGION_PRIORITY = 10;

    /** Cell regions get no hostile mobs: a dark room would otherwise spawn them. */
    private static final Consumer<ProtectedRegion> NO_MOBS = region -> region.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);

    private final JavaPlugin plugin;
    private final File file;
    private final Impound impound;
    /** The market's shop hook, looked up when needed (QuickShop's hook is registered after TheBox creates this). */
    private final Supplier<ShopHook> shops;
    private final TreeMap<Integer, CellTier> tiers = new TreeMap<>();
    private final Map<String, Cell> cells = new TreeMap<>();

    // Settings, from cells.yml.
    private int moveDays = 7;
    private int maxHelpers = 2;
    /** How long a "day" lasts. 1440 is a real day; a small number makes tier moves testable in minutes. */
    private int dayLengthMinutes = 1440;

    public CellManager(JavaPlugin plugin, Impound impound, Supplier<ShopHook> shops) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cells.yml");
        this.impound = impound;
        this.shops = shops;
    }

    public int getMoveDays() { return moveDays; }
    public int getMaxHelpers() { return maxHelpers; }
    public long getDayMillis() { return dayLengthMinutes * 60_000L; }

    // ---------------------------------------------------------------- lookup

    /** Cell ids are used in region ids, YAML keys and commands, so keep them simple. */
    public static boolean isValidId(String id) {
        return id.matches("[a-z0-9_]+");
    }

    public Collection<Cell> getAll() { return cells.values(); }
    public Cell get(String id) { return cells.get(id); }
    public Collection<CellTier> getTiers() { return tiers.values(); }
    public CellTier getTier(int number) { return tiers.get(number); }

    /** The cell's current size, or null if its tier isn't in cells.yml. */
    public CellTier.Size sizeOf(Cell cell) {
        CellTier tier = tiers.get(cell.getTier());
        return tier == null ? null : tier.sizes().get(cell.getSize());
    }

    /**
     * Where the owner arrives: inside the room, just behind the front wall. The tier's front needs a way to open the
     * door from inside (savefront saves the room's first layer, so a button there comes back on every rebuild).
     * Null if the cell isn't set up or its world isn't loaded.
     */
    public Location home(Cell cell) {
        CellTier.Size size = sizeOf(cell);
        return size == null || !cell.isReady() ? null : cell.inside(size);
    }

    /** The player's cell (not counting one they're moving out of), or null. */
    public Cell currentCell(UUID player) {
        for (Cell cell : cells.values()) {
            if (player.equals(cell.getOwner()) && !cell.isMovingOut()) {
                return cell;
            }
        }
        return null;
    }

    /** The cell the player is moving out of, or null. */
    public Cell oldCell(UUID player) {
        for (Cell cell : cells.values()) {
            if (player.equals(cell.getOwner()) && cell.isMovingOut()) {
                return cell;
            }
        }
        return null;
    }

    /** A free cell in the tier that's set up and whose world is loaded, or null. */
    public Cell freeCell(int tier) {
        for (Cell cell : cells.values()) {
            if (cell.getTier() == tier && cell.isFree() && cell.isReady() && cell.getWorld() != null && tiers.containsKey(tier)) {
                return cell;
            }
        }
        return null;
    }

    /** What a player's first cell costs: the price of the first tier's first size in cells.yml (0 is free). */
    public double starterPrice() {
        return tiers.isEmpty() ? 0 : tiers.firstEntry().getValue().sizes().getFirst().price();
    }

    /** True if a starting cell (first tier) is free to hand out. */
    public boolean hasFreeStarterCell() {
        return !tiers.isEmpty() && freeCell(tiers.firstKey()) != null;
    }

    /*
     * Neighbors need at least one block of wall between them: a room at its largest fills its whole plot, so a plot
     * touching another cell's plot or front space would open into it. Front spaces may touch each other (they're
     * both open corridor), just not overlap.
     */

    /** Another cell that a new plot would overlap or touch, or null if there's at least a block between them. */
    public Cell tooCloseToPlot(String world, Box plot, Cell except) {
        return tooClose(world, except, plot.expand(1), plot.expand(1));
    }

    /** Another cell that a new front space would overlap (or touch, for a plot), or null. */
    public Cell tooCloseToFront(String world, Box front, Cell except) {
        return tooClose(world, except, front.expand(1), front);
    }

    private Cell tooClose(String world, Cell except, Box againstPlots, Box againstFronts) {
        for (Cell cell : cells.values()) {
            if (cell != except && cell.getWorldName().equals(world)
                    && (cell.getPlot().overlaps(againstPlots) || (cell.getFront() != null && cell.getFront().overlaps(againstFronts)))) {
                return cell;
            }
        }
        return null;
    }

    /** Sizes are numbered across all tiers, 1 for the starting cell: size n needs thebox.cell.size.n. */
    public int sizeNumber(int tierNumber, int index) {
        int before = 0;
        for (CellTier tier : tiers.headMap(tierNumber).values()) {
            before += tier.sizes().size();
        }
        return before + index + 1;
    }

    /** What the cell's next upgrade is: a bigger room in place, or a move to the next tier. */
    public record Upgrade(CellTier tier, int index, int number, CellTier.Size size, boolean move) {
        public String permission() {
            return "thebox.cell.size." + number;
        }
    }

    /** The cell's next upgrade, or null if it's as big as it gets (or its tier isn't in cells.yml). */
    public Upgrade nextUpgrade(Cell cell) {
        CellTier tier = tiers.get(cell.getTier());
        if (tier == null) {
            return null;
        }
        int next = cell.getSize() + 1;
        if (next < tier.sizes().size()) {
            return new Upgrade(tier, next, sizeNumber(tier.number(), next), tier.sizes().get(next), false);
        }
        Map.Entry<Integer, CellTier> higher = tiers.higherEntry(tier.number());
        if (higher == null) {
            return null;
        }
        CellTier nextTier = higher.getValue();
        return new Upgrade(nextTier, 0, sizeNumber(nextTier.number(), 0), nextTier.sizes().getFirst(), true);
    }

    // ---------------------------------------------------------------- changes (each one saves)

    public void addCell(Cell cell) {
        cells.put(cell.getId(), cell);
        save();
    }

    /**
     * Forgets a free cell and removes its regions. If it was set up, its plot is filled back in with its wall block,
     * so the cell block is solid again, as it was before /thebox cell create (the front space is left alone). Returns
     * false if the plot couldn't be filled because the world isn't loaded.
     */
    public boolean removeCell(Cell cell) {
        World world = cell.getWorld();
        boolean filled = world != null;
        if (filled && cell.isReady()) {
            sendOut(cell, cell.getPlot());
            fill(world, cell.getPlot(), wallBlockData(cell));
            fill(world, cell.getFront(), BlockType.AIR.createBlockData()); // empty, as create found it
        }
        Regions.remove(plugin.getLogger(), world, cell.getRoomRegionId());
        Regions.remove(plugin.getLogger(), world, cell.getFrontRegionId());
        cells.remove(cell.getId());
        save();
        return filled;
    }

    /** Sets a free cell's front space, rebuilds it as a fresh starting room, and creates its regions. */
    public void setFront(Cell cell, Box front, Cell.Front facing) {
        cell.setFront(front, facing);
        rebuild(cell);
        syncRegions(cell);
        save();
    }

    /**
     * Makes {@code front} the tier's cell front and puts it on every free cell of the tier right away; cells with an
     * owner get it the next time they're freed. Returns how many cells got it now.
     */
    public int setTierFront(CellTier tier, List<CellTier.FrontBlock> front) {
        tiers.put(tier.number(), new CellTier(tier.number(), tier.sizes(), List.copyOf(front)));
        int rebuilt = 0;
        for (Cell cell : cells.values()) {
            if (cell.getTier() == tier.number() && cell.isFree() && cell.isReady() && cell.getWorld() != null) {
                rebuild(cell);
                rebuilt++;
            }
        }
        save();
        return rebuilt;
    }

    /** Gives the player a free starting cell (first tier), or returns null if none is free. */
    public Cell assignStarterCell(UUID player) {
        if (tiers.isEmpty()) {
            return null;
        }
        Cell cell = freeCell(tiers.firstKey());
        if (cell != null) {
            cell.assign(player);
            syncRegions(cell);
            save();
        }
        return cell;
    }

    /** Grows the cell to its tier's next size: carves away the wall between the old room and the new one. */
    public void upgradeInPlace(Cell cell) {
        CellTier tier = tiers.get(cell.getTier());
        Box oldRoom = cell.room(tier.sizes().get(cell.getSize()));
        cell.setSize(cell.getSize() + 1);
        carve(cell, oldRoom, cell.room(tier.sizes().get(cell.getSize())));
        syncRegions(cell);
        save();
    }

    /**
     * Gives the owner of {@code from} the free cell {@code to} (in the next tier). The old cell stays theirs for
     * moveDays so they can carry their things over; then it's cleared into their impound (see checkMoves).
     */
    public void move(Cell from, Cell to) {
        to.assign(from.getOwner());
        to.getHelpers().addAll(from.getHelpers());
        from.setReleaseAt(System.currentTimeMillis() + moveDays * getDayMillis());
        syncRegions(to);
        save();
    }

    /**
     * Clears the cell and frees it: everything in it goes to the owner's impound, and the plot is rebuilt as a fresh
     * starting room. Returns the number of stacks impounded, or -1 if the cell's world isn't loaded (nothing changes
     * then: freeing a cell without clearing it would hand the old owner's things to the next one).
     */
    public int release(Cell cell) {
        int stacks = clearAndFree(cell, true);
        if (stacks >= 0) {
            save();
        }
        return stacks;
    }

    /** New season: clears every cell (nothing goes to the impound) and frees it. Returns how many were cleared. */
    public int wipe() {
        int cleared = 0;
        for (Cell cell : cells.values()) {
            if (cell.isReady() && clearAndFree(cell, false) >= 0) {
                cleared++;
            }
        }
        save();
        return cleared;
    }

    public void addHelper(Cell cell, UUID helper) {
        cell.getHelpers().add(helper);
        syncRegions(cell);
        save();
    }

    public void removeHelper(Cell cell, UUID helper) {
        cell.getHelpers().remove(helper);
        syncRegions(cell);
        save();
    }

    private int clearAndFree(Cell cell, boolean toImpound) {
        World world = cell.getWorld();
        CellTier.Size size = sizeOf(cell);
        if (world == null || size == null || !cell.isReady()) {
            return -1;
        }
        Box room = cell.room(size);
        sendOut(cell, room);
        removeOwnFront(cell, tiers.get(cell.getTier()));
        // The front region includes the front wall, so a door or window the owner put in it comes back to them too.
        List<ItemStack> items = new ArrayList<>(RegionClearer.clear(world, room, shops.get()));
        items.addAll(RegionClearer.clear(world, cell.frontRegion(), shops.get()));
        UUID owner = cell.getOwner();
        if (toImpound && owner != null && !items.isEmpty()) {
            impound.add(owner, items);
        }
        cell.assign(null);
        rebuild(cell);
        syncRegions(cell);
        return items.size();
    }

    // ---------------------------------------------------------------- blocks

    /** Before filling part of a cell with wall: sends anyone standing in that part out to the front space. */
    private static void sendOut(Cell cell, Box area) {
        Location outside = cell.visit();
        for (Player player : cell.getWorld().getPlayers()) {
            if (area.contains(player.getLocation())) {
                player.teleport(outside);
            }
        }
    }

    /**
     * Makes the cell new: fills the whole plot with its wall block, empties the front space, carves the starting room,
     * and puts the tier's front on it. Anyone in the plot is sent out to the front space first.
     */
    private void rebuild(Cell cell) {
        World world = cell.getWorld();
        CellTier tier = tiers.get(cell.getTier());
        if (world == null || tier == null || !cell.isReady()) {
            return;
        }
        sendOut(cell, cell.getPlot());
        BlockData air = BlockType.AIR.createBlockData();
        fill(world, cell.getPlot(), wallBlockData(cell));
        fill(world, cell.getFront(), air);
        fill(world, cell.room(tier.sizes().getFirst()), air);
        // Placed last, without physics: buttons and torches need the wall to be there, and doors need both halves.
        int roomSide = cell.roomSide(tier.sizes().getFirst());
        for (CellTier.FrontBlock entry : tier.front()) {
            Block block = cell.frontBlock(world, entry.side() + roomSide, entry.up(), entry.out());
            if (fitsFront(cell, tier, block, entry.out())) {
                BlockData data = entry.data().clone();
                data.rotate(cell.getFacing().fromNorth());
                block.setBlockData(data, false);
            }
        }
    }

    /**
     * Before a cell is cleared into its owner's impound: removes the parts of its front that are still as TheBox put
     * them (the wall block in the front wall, and the tier's front), so only what the owner added is impounded.
     * Otherwise every owner leaving would get a free door, and the front wall's blocks. Compared by block type, so an
     * opened door or a pressed button still counts as the cell's own.
     */
    private void removeOwnFront(Cell cell, CellTier tier) {
        World world = cell.getWorld();
        if (world == null || tier == null) {
            return;
        }
        BlockData air = BlockType.AIR.createBlockData();
        Set<Location> tierFront = new HashSet<>();
        int roomSide = cell.roomSide(tier.sizes().getFirst());
        for (CellTier.FrontBlock entry : tier.front()) {
            Block block = cell.frontBlock(world, entry.side() + roomSide, entry.up(), entry.out());
            if (fitsFront(cell, tier, block, entry.out())) {
                tierFront.add(block.getLocation());
                if (block.getType() == entry.data().getMaterial()) {
                    block.setBlockData(air, false);
                }
            }
        }
        Material wall = wallBlockData(cell).getMaterial();
        int width = Cell.plotWidth(cell.getPlot(), cell.getFacing());
        for (int side = 0; side < width; side++) {
            for (int up = 0; up < cell.getPlot().sizeY(); up++) {
                Block block = cell.frontBlock(world, side, up, 0);
                if (!tierFront.contains(block.getLocation()) && block.getType() == wall) {
                    block.setBlockData(air, false);
                }
            }
        }
    }

    /**
     * Whether a block of a tier's front has a place in this cell: in its front wall, its front space (which may be
     * shallower than the one the front was saved from), or the first layer of its starting room.
     */
    private static boolean fitsFront(Cell cell, CellTier tier, Block block, int out) {
        Box area = out == 0 ? cell.getPlot() : out > 0 ? cell.getFront() : cell.room(tier.sizes().getFirst());
        return area.contains(block.getX(), block.getY(), block.getZ());
    }

    /**
     * Removes the wall between the old room and the new one. Blocks inside the old room aren't touched, so
     * everything the owner built stays. Things hung on an old wall (torches, signs) are left floating, and
     * drop as items the next time a neighboring block changes.
     */
    private void carve(Cell cell, Box oldRoom, Box newRoom) {
        World world = cell.getWorld();
        if (world == null) {
            return;
        }
        BlockData air = BlockType.AIR.createBlockData();
        for (int x = newRoom.minX(); x <= newRoom.maxX(); x++) {
            for (int y = newRoom.minY(); y <= newRoom.maxY(); y++) {
                for (int z = newRoom.minZ(); z <= newRoom.maxZ(); z++) {
                    if (!oldRoom.contains(x, y, z)) {
                        world.getBlockAt(x, y, z).setBlockData(air, false);
                    }
                }
            }
        }
    }

    /** Sets every block in the box. applyPhysics=false: no neighbor updates, so nothing cascades or drops. */
    private static void fill(World world, Box box, BlockData data) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    world.getBlockAt(x, y, z).setBlockData(data, false);
                }
            }
        }
    }

    private static BlockData wallBlockData(Cell cell) {
        NamespacedKey key = NamespacedKey.fromString(cell.getWallBlock());
        BlockType type = key == null ? null : Registry.BLOCK.get(key);
        return (type != null ? type : BlockType.STONE_BRICKS).createBlockData();
    }

    // ---------------------------------------------------------------- WorldGuard

    /**
     * Makes the cell's two regions (room and front) match the cell: the owner and helpers are members, nobody while
     * it's free. A cell that isn't set up has no regions.
     */
    private void syncRegions(Cell cell) {
        World world = cell.getWorld();
        CellTier.Size size = sizeOf(cell);
        if (!cell.isReady() || size == null) {
            Regions.remove(plugin.getLogger(), world, cell.getRoomRegionId());
            Regions.remove(plugin.getLogger(), world, cell.getFrontRegionId());
            return;
        }
        List<UUID> members = new ArrayList<>();
        if (cell.getOwner() != null) {
            members.add(cell.getOwner());
            members.addAll(cell.getHelpers());
        }
        Regions.sync(plugin.getLogger(), world, cell.getRoomRegionId(), cell.room(size), REGION_PRIORITY, members, NO_MOBS);
        Regions.sync(plugin.getLogger(), world, cell.getFrontRegionId(), cell.frontRegion(), REGION_PRIORITY, members, NO_MOBS);
    }

    // ---------------------------------------------------------------- joining

    /**
     * Reminds players about an old cell they're moving out of, and tells players without a cell how to get one.
     * Cells are only handed out when a player asks (/cell), so admins and players who join once and never come
     * back don't use one up: nothing takes a cell back from an inactive player.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Cell old = oldCell(uuid);
        if (old != null) {
            player.sendRichMessage("<gold>You're moving out of your old cell: it's cleared in <time>, and what's left goes to your impound. "
                + "<yellow>/cell old</yellow> takes you there.", Placeholder.unparsed("time", Durations.format(old.getReleaseAt() - System.currentTimeMillis())));
        }
        if (currentCell(uuid) == null && old == null && !cells.isEmpty()) { // cells.isEmpty(): this server doesn't use cells (yet)
            player.sendRichMessage("<gray>You don't have a cell yet. Type <yellow>/cell</yellow> to get your own.");
        }
    }

    /** Logs a warning and tells online admins. */
    void warnAdmins(String message) {
        plugin.getLogger().warning(message);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("thebox.admin")) {
                online.sendRichMessage("<red>[TheBox] <message>", Placeholder.unparsed("message", message));
            }
        }
    }

    // ---------------------------------------------------------------- moving timer

    /** Checks every 5 seconds for old cells whose moving time is up. */
    public void startTimer() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkMoves, 100L, 100L);
    }

    private void checkMoves() {
        long now = System.currentTimeMillis();
        for (Cell cell : List.copyOf(cells.values())) {
            if (!cell.isMovingOut() || now < cell.getReleaseAt() || cell.getWorld() == null) {
                continue;
            }
            UUID owner = cell.getOwner();
            int stacks = release(cell);
            if (stacks < 0) {
                continue;
            }
            plugin.getLogger().info("Cleared old cell " + cell.getId() + " of " + plugin.getServer().getOfflinePlayer(owner).getName()
                + " after their move (" + stacks + " stacks impounded).");
            Player online = plugin.getServer().getPlayer(owner);
            if (online != null) {
                online.sendRichMessage("<gold>Your old cell was cleared." + (stacks > 0 ? " " + stacks + " stack(s) went to your impound: /cell claim" : ""));
            }
        }
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        tiers.clear();
        cells.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        moveDays = Math.max(0, yaml.getInt("settings.move-days", 7));
        maxHelpers = Math.max(0, yaml.getInt("settings.max-helpers", 2));
        dayLengthMinutes = Math.max(1, yaml.getInt("settings.day-length-minutes", 1440));

        ConfigurationSection tierSection = yaml.getConfigurationSection("tiers");
        if (tierSection == null) {
            addDefaultTiers();
        } else {
            for (String key : tierSection.getKeys(false)) {
                int number;
                try {
                    number = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("cells.yml: skipping tier '" + key + "': tiers are numbered (1, 2, 3).");
                    continue;
                }
                List<CellTier.Size> sizes = readSizes(key, tierSection.getMapList(key + ".sizes"));
                if (sizes.isEmpty()) {
                    plugin.getLogger().warning("cells.yml: tier " + key + " has no sizes; skipping it.");
                    continue;
                }
                tiers.put(number, new CellTier(number, sizes, readFront(key, tierSection.getStringList(key + ".front"))));
            }
        }

        ConfigurationSection cellSection = yaml.getConfigurationSection("cells");
        if (cellSection != null) {
            for (String id : cellSection.getKeys(false)) {
                Cell cell = loadCell(id, cellSection.getConfigurationSection(id));
                if (cell != null) {
                    cells.put(id, cell);
                }
            }
        }
        // Make WorldGuard match cells.yml (recreates deleted regions, fixes members after hand edits).
        cells.values().forEach(this::syncRegions);
        if (tierSection == null) {
            save(); // writes the default tiers, so admins can see and edit them
        }
    }

    /**
     * The default sizes (see design: a size every third rank, A to Y). Each size costs about the default cost of the
     * rank that unlocks it (ranks.yml), so every size costs more than the one before. The first cell's 2000 is a small first
     * goal, so players play a while before they get a cell. README section 11 lists them.
     */
    private void addDefaultTiers() {
        tiers.put(1, new CellTier(1, List.of(
            new CellTier.Size(3, 3, 3, 2_000), new CellTier.Size(5, 5, 3, 19_000), new CellTier.Size(7, 7, 3, 55_000)), List.of()));
        tiers.put(2, new CellTier(2, List.of(
            new CellTier.Size(11, 11, 6, 550_000), new CellTier.Size(13, 13, 6, 1_400_000), new CellTier.Size(15, 15, 6, 3_500_000)), List.of()));
        tiers.put(3, new CellTier(3, List.of(
            new CellTier.Size(19, 19, 8, 7_300_000), new CellTier.Size(21, 21, 8, 15_000_000), new CellTier.Size(23, 23, 8, 30_000_000)), List.of()));
    }

    /** Reads a tier's front: one "side up out block-data" line per block (see CellTier.FrontBlock). */
    private List<CellTier.FrontBlock> readFront(String tier, List<String> lines) {
        List<CellTier.FrontBlock> front = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split(" ", 4);
            try {
                front.add(new CellTier.FrontBlock(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    plugin.getServer().createBlockData(parts[3])));
            } catch (RuntimeException e) { // a bad number, a missing part, or block data this Minecraft version doesn't know
                plugin.getLogger().warning("cells.yml: tier " + tier + ": skipping front block '" + line + "': " + e.getMessage());
            }
        }
        return front;
    }

    /** Reads a tier's sizes. A size smaller than the one before it is raised to match: cells only ever grow. */
    private List<CellTier.Size> readSizes(String tier, List<Map<?, ?>> maps) {
        List<CellTier.Size> sizes = new ArrayList<>();
        int width = 1;
        int depth = 1;
        int height = 2;
        for (Map<?, ?> map : maps) {
            int w = Math.max(width, number(map, "width", 1).intValue());
            int d = Math.max(depth, number(map, "depth", 1).intValue());
            int h = Math.max(height, number(map, "height", 2).intValue());
            if (w != number(map, "width", 1).intValue() || d != number(map, "depth", 1).intValue() || h != number(map, "height", 2).intValue()) {
                plugin.getLogger().warning("cells.yml: a size in tier " + tier + " is smaller than the one before it; raised to " + w + "x" + d + "x" + h + ".");
            }
            sizes.add(new CellTier.Size(w, d, h, Math.max(0, number(map, "price", 0).doubleValue())));
            width = w;
            depth = d;
            height = h;
        }
        return sizes;
    }

    private static Number number(Map<?, ?> map, String key, Number fallback) {
        return map.get(key) instanceof Number n ? n : fallback;
    }

    private Cell loadCell(String id, ConfigurationSection s) {
        Box plot = s == null ? null : Box.read(s, "plot");
        if (s == null || plot == null || !isValidId(id) || !s.isString("world") || !s.isInt("tier")) {
            plugin.getLogger().warning("cells.yml: skipping cell '" + id + "': invalid id or entry.");
            return null;
        }
        Cell cell = new Cell(id, s.getInt("tier"), s.getString("world"), plot, s.getString("wall-block", "minecraft:stone_bricks"));
        Box front = Box.read(s, "front");
        if (front != null) {
            Cell.Front facing = Cell.Front.of(plot, front);
            if (facing == null) {
                plugin.getLogger().warning("cells.yml: cell '" + id + "': its front space doesn't lie against the plot; set it again with /thebox cell setfront.");
            } else {
                cell.setFront(front, facing);
            }
        }
        String owner = s.getString("owner");
        if (owner != null) {
            try {
                cell.assign(UUID.fromString(owner));
                cell.setSize(s.getInt("size", 1) - 1);
                for (String helper : s.getStringList("helpers")) {
                    cell.getHelpers().add(UUID.fromString(helper));
                }
                cell.setReleaseAt(s.getLong("release-at"));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("cells.yml: cell '" + id + "' has a bad player id: " + e.getMessage());
            }
        }
        CellTier tier = tiers.get(cell.getTier());
        if (tier == null) {
            plugin.getLogger().warning("cells.yml: cell '" + id + "' is in tier " + cell.getTier() + ", which isn't in the tiers list.");
        } else {
            cell.setSize(Math.clamp(cell.getSize(), 0, tier.sizes().size() - 1)); // in case a tier lost sizes
        }
        return cell;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox cells. Edited by /thebox cell commands; upgrades and moves change it too.",
            "You can edit it by hand: run /thebox cell reload right after, or the next change overwrites your edits.",
            "tiers: the sizes a cell grows through (room floor width x depth, and height) and each one's price.",
            "  The first size of tier 1 is a player's first cell (/cell buy; price 0 makes it free); the first size of a later tier is a move to a new cell.",
            "  Only change sizes before cells are built: plots are built for the largest size, and cells never shrink.",
            "  front: the tier's cell front (door, window, button...), saved with /thebox cell savefront. Don't edit it by hand.",
            "settings.day-length-minutes: how long a \"day\" lasts for move-days. Set it to e.g. 2 to test moving quickly."));
        yaml.set("settings.move-days", moveDays);
        yaml.set("settings.max-helpers", maxHelpers);
        yaml.set("settings.day-length-minutes", dayLengthMinutes);
        for (CellTier tier : tiers.values()) {
            List<Map<String, Object>> sizes = new ArrayList<>();
            for (CellTier.Size size : tier.sizes()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("width", size.width());
                map.put("depth", size.depth());
                map.put("height", size.height());
                map.put("price", size.price());
                sizes.add(map);
            }
            yaml.set("tiers." + tier.number() + ".sizes", sizes);
            if (!tier.front().isEmpty()) {
                yaml.set("tiers." + tier.number() + ".front", tier.front().stream()
                    .map(b -> b.side() + " " + b.up() + " " + b.out() + " " + b.data().getAsString()).toList());
            }
        }
        for (Cell cell : cells.values()) {
            String path = "cells." + cell.getId();
            yaml.set(path + ".tier", cell.getTier());
            yaml.set(path + ".world", cell.getWorldName());
            cell.getPlot().write(yaml, path + ".plot");
            yaml.set(path + ".wall-block", cell.getWallBlock());
            if (cell.getFront() != null) {
                cell.getFront().write(yaml, path + ".front");
            }
            if (!cell.isFree()) {
                yaml.set(path + ".owner", cell.getOwner().toString());
                yaml.set(path + ".size", cell.getSize() + 1);
                yaml.set(path + ".helpers", cell.getHelpers().stream().map(UUID::toString).toList());
                if (cell.isMovingOut()) {
                    yaml.set(path + ".release-at", cell.getReleaseAt());
                }
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save cells.yml: " + e.getMessage());
        }
    }
}
