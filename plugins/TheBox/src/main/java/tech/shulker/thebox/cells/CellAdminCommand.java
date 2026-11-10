// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import static tech.shulker.thebox.utils.Errors.fail;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.regions.Region;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.regions.Box;
import tech.shulker.thebox.utils.Durations;
import tech.shulker.thebox.utils.Selections;

/** The admin command: /thebox cell ... Mounted under /thebox. */
public class CellAdminCommand {

    /** Plots are checked and rebuilt block by block, so keep them a sensible size. */
    private static final long MAX_PLOT_VOLUME = 100_000;

    /** How far from the wall an admin may stand for {@code create ... here}: the deepest front space it makes. */
    private static final int MAX_FRONT_DEPTH = 3;

    private final CellManager cells;

    public CellAdminCommand(CellManager cells) {
        this.cells = cells;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("cell")
            .executes(this::help)
            .then(Commands.literal("create")
                .then(Commands.argument("tier", IntegerArgumentType.integer(1))
                    .suggests((ctx, builder) -> {
                        cells.getTiers().forEach(tier -> builder.suggest(tier.number()));
                        return builder.buildFuture();
                    })
                    .then(Commands.argument("cell", StringArgumentType.word())
                        .executes(this::create)
                        .then(Commands.literal("here").executes(this::createHere)))))
            .then(Commands.literal("setfront").then(cellArgument().executes(this::setFront)))
            .then(Commands.literal("savefront").then(cellArgument().executes(this::saveFront)))
            .then(Commands.literal("list").executes(this::list))
            .then(Commands.literal("evict").then(cellArgument().executes(this::evict)))
            .then(Commands.literal("delete").then(cellArgument().executes(this::delete)))
            .then(Commands.literal("wipe")
                .executes(this::wipeInfo)
                .then(Commands.literal("confirm").executes(this::wipe)))
            .then(Commands.literal("reload").executes(this::reload))
            .build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> cellArgument() {
        return Commands.argument("cell", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                for (Cell cell : cells.getAll()) {
                    if (cell.getId().startsWith(builder.getRemainingLowerCase())) {
                        builder.suggest(cell.getId());
                    }
                }
                return builder.buildFuture();
            });
    }

    private Cell getCell(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "cell");
        Cell cell = cells.get(id);
        if (cell == null) {
            throw fail("Unknown cell: <id>", Placeholder.unparsed("id", id));
        }
        return cell;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>TheBox cell commands:</gold>
            <yellow>/thebox cell create \\<tier> \\<cell> here</yellow> <gray>- stand on the floor facing the wall, lined up with the cell's middle: makes the plot and front space, and carves the starting room</gray>
            <yellow>/thebox cell create \\<tier> \\<cell></yellow> <gray>- or make your selection a cell's plot: solid blocks, room for the tier's largest room plus the front wall</gray>
            <yellow>/thebox cell setfront \\<cell></yellow> <gray>- make your selection its front space (empty, against one side of the plot); carves the starting room</gray>
            <yellow>/thebox cell savefront \\<cell></yellow> <gray>- make a free cell's front (door, window, button...) its tier's front, put back on every rebuild</gray>
            <yellow>/thebox cell list</yellow> <gray>- cells and owners</gray>
            <yellow>/thebox cell evict \\<cell></yellow> <gray>- clear a cell into its owner's impound and free it</gray>
            <yellow>/thebox cell delete \\<cell></yellow> <gray>- forget a free cell (blocks stay)</gray>
            <yellow>/thebox cell wipe</yellow> <gray>- new season: clear and free every cell</gray>
            <yellow>/thebox cell reload</yellow> <gray>- re-read cells.yml</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- defining cells

    /** /thebox cell create <tier> <cell>: the plot is the WorldEdit selection; the front space comes next, with setfront. */
    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        String id = newCellId(ctx);
        CellTier tier = tierOf(ctx);
        Region selection = Selections.get(player);
        if (selection == null) {
            throw fail("Make a WorldEdit selection first (//wand, then click two corners), or stand in front of the wall and add <yellow>here</yellow>.");
        }
        Box plot = Box.of(selection);
        World world = player.getWorld();
        String wallBlock = checkPlot(world, plot);

        cells.addCell(new Cell(id, tier.number(), world.getName(), plot, wallBlock));
        player.sendRichMessage("<green>Created cell <white>" + id + "</white> in tier " + tier.number() + " (plot " + plot.describe()
            + ", wall block " + wallBlock + "). Now select its front space and run <yellow>/thebox cell setfront " + id + "</yellow>.");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /thebox cell create <tier> <cell> here: works out the plot and the front space from where the admin stands (on
     * the floor in front of the wall, facing it, lined up with the middle of the cell). The plot starts at the first
     * block ahead, is centered on them, and is just big enough for the tier's largest room plus the front wall. The
     * front space is the air from the wall back to where they stand, as wide and tall as the plot.
     */
    private int createHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        String id = newCellId(ctx);
        CellTier tier = tierOf(ctx);
        World world = player.getWorld();
        Block feet = player.getLocation().getBlock();
        if (feet.getRelative(BlockFace.DOWN).isEmpty()) {
            throw fail("Stand on the floor in front of the cell: the room's floor will be level with it.");
        }
        BlockFace ahead = player.getFacing(); // the nearest of north, south, east and west
        int dx = ahead.getModX();
        int dz = ahead.getModZ();
        int distance = 0; // to the front wall: the first block ahead at foot height
        for (int k = 1; k <= MAX_FRONT_DEPTH && distance == 0; k++) {
            if (!feet.getRelative(dx * k, 0, dz * k).isEmpty()) {
                distance = k;
            }
        }
        if (distance == 0) {
            throw fail("Face the cell's wall, standing at most " + MAX_FRONT_DEPTH + " blocks from it.");
        }

        // Side to side is the axis you're not facing along. The plot is centered on you (if its width is even,
        // the extra block goes to the east or south).
        int sideX = dz != 0 ? 1 : 0;
        int sideZ = dx != 0 ? 1 : 0;
        int width = tier.maxWidth();
        int depth = tier.maxDepth() + 1; // the largest room plus the front wall
        int height = tier.maxHeight();
        int before = (width - 1) / 2;
        int after = width - 1 - before;
        int x = feet.getX();
        int y = feet.getY();
        int z = feet.getZ();
        Box front = Box.between(
            x - sideX * before, y, z - sideZ * before,
            x + dx * (distance - 1) + sideX * after, y + height - 1, z + dz * (distance - 1) + sideZ * after);
        Box plot = Box.between(
            x + dx * distance - sideX * before, y, z + dz * distance - sideZ * before,
            x + dx * (distance + depth - 1) + sideX * after, y + height - 1, z + dz * (distance + depth - 1) + sideZ * after);
        Cell.Front facing = Cell.Front.of(plot, front); // never null: the front space lies against the plot's near side

        // Check everything before changing anything, so a refusal never leaves half a cell behind.
        String wallBlock = checkPlot(world, plot);
        checkFront(world, front, null);
        Cell cell = new Cell(id, tier.number(), world.getName(), plot, wallBlock);
        cells.addCell(cell);
        cells.setFront(cell, front, facing);
        player.sendRichMessage("<green>Created cell <white>" + id + "</white> in tier " + tier.number() + ": plot " + plot.describe()
            + " (wall block " + wallBlock + "), front facing " + facing.name().toLowerCase() + ", front space " + distance
            + " deep. Its starting room is carved." + (cell.isFree() ? "" : " It was handed to a player waiting for a cell.")
            + " <gray>Wrong spot? <yellow>/thebox cell delete " + id + "</yellow> fills it back in.");
        return Command.SINGLE_SUCCESS;
    }

    /** A new cell's id from the command: valid, and not taken yet. */
    private String newCellId(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "cell");
        if (!CellManager.isValidId(id)) {
            throw fail("Cell ids may only use lowercase letters, numbers and _.");
        }
        if (cells.get(id) != null) {
            throw fail("A cell called <id> already exists.", Placeholder.unparsed("id", id));
        }
        return id;
    }

    private CellTier tierOf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int number = IntegerArgumentType.getInteger(ctx, "tier");
        CellTier tier = cells.getTier(number);
        if (tier == null) {
            throw fail("There's no tier " + number + " in cells.yml.");
        }
        return tier;
    }

    /**
     * Checks a new plot: not too big, a block of wall between it and other cells, and completely solid (TheBox
     * carves the rooms). Returns its most common block, remembered as the wall block so a freed cell is rebuilt the same.
     */
    private String checkPlot(World world, Box plot) throws CommandSyntaxException {
        if (plot.volume() > MAX_PLOT_VOLUME) {
            throw fail("That's " + plot.volume() + " blocks; a plot can be at most " + MAX_PLOT_VOLUME + ".");
        }
        Cell neighbor = cells.tooCloseToPlot(world.getName(), plot, null);
        if (neighbor != null) {
            throw fail("That plot overlaps or touches cell <id>. Keep at least one block of wall between cells.",
                Placeholder.unparsed("id", neighbor.getId()));
        }
        Map<String, Integer> counts = new HashMap<>();
        int empty = 0;
        for (int x = plot.minX(); x <= plot.maxX(); x++) {
            for (int y = plot.minY(); y <= plot.maxY(); y++) {
                for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isEmpty()) {
                        empty++;
                    } else {
                        counts.merge(block.getType().key().asString(), 1, Integer::sum);
                    }
                }
            }
        }
        if (empty > 0) {
            throw fail(empty + " empty block(s) in the plot (" + plot.corners() + "). Build it solid: TheBox carves the rooms.");
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    /** Checks a front space: clear of other cells, and completely empty (everything in it would belong to the owner). */
    private void checkFront(World world, Box front, Cell cell) throws CommandSyntaxException {
        Cell neighbor = cells.tooCloseToFront(world.getName(), front, cell);
        if (neighbor != null) {
            throw fail("That front space overlaps cell <id>'s front space or touches its plot.", Placeholder.unparsed("id", neighbor.getId()));
        }
        int blocks = 0;
        for (int x = front.minX(); x <= front.maxX(); x++) {
            for (int y = front.minY(); y <= front.maxY(); y++) {
                for (int z = front.minZ(); z <= front.maxZ(); z++) {
                    if (!world.getBlockAt(x, y, z).isEmpty()) {
                        blocks++;
                    }
                }
            }
        }
        if (blocks > 0) {
            throw fail(blocks + " block(s) in the front space (" + front.corners() + "). It must be empty air: "
                + "everything in it would belong to the cell's owner.");
        }
    }

    private int setFront(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell cell = getCell(ctx);
        if (!cell.isFree()) {
            throw fail("Cell <id> belongs to <name>. Evict them first.", Placeholder.unparsed("id", cell.getId()),
                Placeholder.unparsed("name", nameOf(player, cell.getOwner())));
        }
        CellTier tier = cells.getTier(cell.getTier());
        if (tier == null) {
            throw fail("This cell's tier (" + cell.getTier() + ") isn't in cells.yml.");
        }
        World world = player.getWorld();
        if (!world.getName().equals(cell.getWorldName())) {
            throw fail("This cell is in world " + cell.getWorldName() + ".");
        }
        Region selection = Selections.get(player);
        if (selection == null) {
            throw fail("Make a WorldEdit selection first (//wand, then click two corners).");
        }
        Box front = Box.of(selection);
        Cell.Front facing = Cell.Front.of(cell.getPlot(), front);
        if (facing == null) {
            throw fail("The front space must lie right against one side of the plot (not above or below it), within the plot's width and height.");
        }
        int width = Cell.plotWidth(cell.getPlot(), facing);
        int depth = Cell.plotDepth(cell.getPlot(), facing);
        int height = cell.getPlot().sizeY();
        if (width < tier.maxWidth() || depth < tier.maxDepth() + 1 || height < tier.maxHeight()) {
            throw fail("Seen from this front, the plot is " + width + " wide, " + depth + " deep and " + height + " tall. Tier " + tier.number()
                + "'s largest room needs " + tier.maxWidth() + " wide, " + (tier.maxDepth() + 1) + " deep (with the front wall) and "
                + tier.maxHeight() + " tall.");
        }
        checkFront(world, front, cell);

        cells.setFront(cell, front, facing);
        player.sendRichMessage("<green>Cell <white>" + cell.getId() + "</white> is ready: its front faces " + facing.name().toLowerCase()
            + " and its starting room is carved. Regions: <white>" + cell.getRoomRegionId() + "</white> and <white>" + cell.getFrontRegionId() + "</white>."
            + (cell.isFree() ? "" : " It was handed to a player waiting for a cell."));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /thebox cell savefront <cell>: saves a free cell's front as its tier's front. That's what differs from a plain
     * cell in the front wall (from its wall block), in the front space and on the inside of the front wall (from air).
     * Every free cell in the tier gets it now, and every cell gets it each time it's rebuilt.
     */
    private int saveFront(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell cell = getCell(ctx);
        if (!cell.isReady()) {
            throw fail("Cell <id> has no front space yet.", Placeholder.unparsed("id", cell.getId()));
        }
        if (!cell.isFree()) {
            throw fail("Cell <id> belongs to <name>. Decorate a free cell's front instead.", Placeholder.unparsed("id", cell.getId()),
                Placeholder.unparsed("name", nameOf(player, cell.getOwner())));
        }
        CellTier tier = cells.getTier(cell.getTier());
        if (tier == null) {
            throw fail("This cell's tier (" + cell.getTier() + ") isn't in cells.yml.");
        }
        World world = cell.getWorld();
        if (world == null) {
            throw fail("This cell's world (" + cell.getWorldName() + ") isn't loaded.");
        }
        CellTier.Size first = tier.sizes().getFirst();
        int roomSide = cell.roomSide(first);
        int width = Cell.plotWidth(cell.getPlot(), cell.getFacing());
        int height = cell.getPlot().sizeY();
        List<CellTier.FrontBlock> front = new ArrayList<>();
        // out: -1 is the starting room's first layer (inside the front wall), 0 the front wall, 1+ the front space.
        for (int out = -1; out <= cell.frontDepth(); out++) {
            for (int side = 0; side < width; side++) {
                for (int up = 0; up < height; up++) {
                    boolean inFrontOfRoom = side >= roomSide && side < roomSide + first.width() && up < first.height();
                    if (out == -1 && !inFrontOfRoom) {
                        continue; // only the starting room is inside; the rest is solid wall
                    }
                    Block block = cell.frontBlock(world, side, up, out);
                    if (out > 0 && !cell.getFront().contains(block.getX(), block.getY(), block.getZ())) {
                        continue; // a selection-made front space can be narrower than the plot
                    }
                    boolean plain = out == 0 ? block.getType().key().asString().equals(cell.getWallBlock()) : block.isEmpty();
                    if (plain) {
                        continue;
                    }
                    String where = " at " + block.getX() + " " + block.getY() + " " + block.getZ();
                    if (block.getState(false) instanceof TileState) {
                        // Only the block itself is saved, not what's in it: a sign would come back blank, a banner plain.
                        throw fail("The " + block.getType().key().value() + where + " can't be part of a front: signs, banners, heads and "
                            + "containers would come back empty. Take it out and save again.");
                    }
                    BlockData data = block.getBlockData();
                    if (out == 0 && !data.isOccluding() && !inFrontOfRoom) {
                        throw fail("The " + block.getType().key().value() + where + " would open onto solid wall while the cell is at its "
                            + "smallest size (" + first.floor() + "). Doors, windows and gaps go in the middle " + first.width()
                            + " blocks of the front wall.");
                    }
                    BlockData stored = data.clone();
                    stored.rotate(cell.getFacing().toNorth());
                    front.add(new CellTier.FrontBlock(side - roomSide, up, out, stored));
                }
            }
        }

        int rebuilt = cells.setTierFront(tier, front);
        long owned = cells.getAll().stream().filter(c -> c.getTier() == tier.number() && !c.isFree()).count();
        player.sendRichMessage(front.isEmpty()
            ? "<green>Tier " + tier.number() + " has a plain front again. Rebuilt " + rebuilt + " free cell(s)."
            : "<green>Saved cell <white>" + cell.getId() + "</white>'s front as tier " + tier.number() + "'s (" + front.size() + " blocks) and put it on "
                + rebuilt + " free cell(s)." + (owned > 0 ? " The " + owned + " cell(s) with an owner get it when they're next freed." : ""));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- managing cells

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (cells.getAll().isEmpty()) {
            sender.sendRichMessage("<gray>No cells yet. Create one with /thebox cell create \\<tier> \\<cell>.");
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        sender.sendRichMessage("<gold>Cells:");
        for (Cell cell : cells.getAll()) {
            CellTier.Size size = cells.sizeOf(cell);
            String status;
            if (!cell.isReady()) {
                status = "<red>no front space yet</red>";
            } else if (cell.isFree()) {
                status = "<green>free</green>";
            } else {
                UUID owner = cell.getOwner();
                String name = sender.getServer().getOfflinePlayer(owner).getName();
                status = (name != null ? name : owner.toString()) + "'s, " + (size != null ? size.floor() : "?")
                    + (cell.isMovingOut() ? ", cleared in " + Durations.format(cell.getReleaseAt() - now) + " (owner moved)" : "");
            }
            sender.sendRichMessage("<yellow>" + cell.getId() + "</yellow> <gray>- tier " + cell.getTier() + ", " + status);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int evict(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Cell cell = getCell(ctx);
        if (cell.isFree()) {
            throw fail("Cell <id> is free.", Placeholder.unparsed("id", cell.getId()));
        }
        UUID owner = cell.getOwner();
        int stacks = cells.release(cell);
        if (stacks < 0) {
            throw fail("That cell's world isn't loaded (or its tier is missing from cells.yml).");
        }
        CommandSender sender = ctx.getSource().getSender();
        sender.sendRichMessage("<green>Cleared cell <white>" + cell.getId() + "</white>: " + stacks + " stack(s) went to the owner's impound.");
        Player online = sender.getServer().getPlayer(owner);
        if (online != null) {
            online.sendRichMessage("<red>An admin cleared your cell. " + stacks + " stack(s) are waiting for you: /cell claim");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Cell cell = getCell(ctx);
        if (!cell.isFree()) {
            throw fail("Cell <id> has an owner. Evict them first.", Placeholder.unparsed("id", cell.getId()));
        }
        boolean filled = cells.removeCell(cell);
        ctx.getSource().getSender().sendRichMessage("<green>Deleted cell <white>" + cell.getId() + "</white> and its regions. "
            + (filled ? "Its plot is solid again." : "Its world isn't loaded, so its blocks were left as they are."));
        return Command.SINGLE_SUCCESS;
    }

    private int wipeInfo(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("<gold>This clears every cell for a new season</gold><gray>: everything in them is deleted "
            + "(nothing goes to the impound), and every cell is rebuilt as an empty starting room. Players get a new cell when they next join. "
            + "Back up first. Run <yellow>/thebox cell wipe confirm</yellow> to do it.");
        return Command.SINGLE_SUCCESS;
    }

    private int wipe(CommandContext<CommandSourceStack> ctx) {
        int cleared = cells.wipe();
        ctx.getSource().getSender().sendRichMessage("<green>Wiped " + cleared + " cell(s).");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        cells.load();
        ctx.getSource().getSender().sendRichMessage("<green>Reloaded cells.yml: " + cells.getAll().size() + " cell(s), " + cells.getTiers().size() + " tier(s).");
        return Command.SINGLE_SUCCESS;
    }

    private static String nameOf(Player viewer, UUID uuid) {
        String name = viewer.getServer().getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString();
    }
}
