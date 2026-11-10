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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.regions.Box;
import tech.shulker.thebox.utils.Durations;
import tech.shulker.thebox.utils.Selections;

/** The admin command: /thebox cell ... Mounted under /thebox. */
public class CellAdminCommand {

    /** Plots are checked and rebuilt block by block, so keep them a sensible size. */
    private static final long MAX_PLOT_VOLUME = 100_000;

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
                    .then(Commands.argument("cell", StringArgumentType.word()).executes(this::create))))
            .then(Commands.literal("setfront").then(cellArgument().executes(this::setFront)))
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
            <yellow>/thebox cell create \\<tier> \\<cell></yellow> <gray>- make your selection a cell's plot: solid blocks, room for the tier's largest room plus the front wall</gray>
            <yellow>/thebox cell setfront \\<cell></yellow> <gray>- make your selection its front space (empty, against one side of the plot); carves the starting room</gray>
            <yellow>/thebox cell list</yellow> <gray>- cells and owners</gray>
            <yellow>/thebox cell evict \\<cell></yellow> <gray>- clear a cell into its owner's impound and free it</gray>
            <yellow>/thebox cell delete \\<cell></yellow> <gray>- forget a free cell (blocks stay)</gray>
            <yellow>/thebox cell wipe</yellow> <gray>- new season: clear and free every cell</gray>
            <yellow>/thebox cell reload</yellow> <gray>- re-read cells.yml</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- defining cells

    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        int tierNumber = IntegerArgumentType.getInteger(ctx, "tier");
        String id = StringArgumentType.getString(ctx, "cell");
        if (!CellManager.isValidId(id)) {
            throw fail("Cell ids may only use lowercase letters, numbers and _.");
        }
        if (cells.get(id) != null) {
            throw fail("A cell called <id> already exists.", Placeholder.unparsed("id", id));
        }
        if (cells.getTier(tierNumber) == null) {
            throw fail("There's no tier " + tierNumber + " in cells.yml.");
        }
        Region selection = Selections.get(player);
        if (selection == null) {
            throw fail("Make a WorldEdit selection first (//wand, then click two corners).");
        }
        Box plot = Box.of(selection);
        World world = player.getWorld();
        if (plot.volume() > MAX_PLOT_VOLUME) {
            throw fail("That's " + plot.volume() + " blocks; a plot can be at most " + MAX_PLOT_VOLUME + ".");
        }
        if (cells.overlapsAny(world.getName(), plot, null)) {
            throw fail("That selection overlaps another cell.");
        }
        // The plot must be solid: TheBox carves the rooms. Its most common block is remembered as the wall block,
        // so a freed cell is rebuilt the same.
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
            throw fail(empty + " empty block(s) in the selection. Build the plot solid: TheBox carves the rooms.");
        }
        String wallBlock = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();

        cells.addCell(new Cell(id, tierNumber, world.getName(), plot, wallBlock));
        player.sendRichMessage("<green>Created cell <white>" + id + "</white> in tier " + tierNumber + " (plot " + plot.describe()
            + ", wall block " + wallBlock + "). Now select its front space and run <yellow>/thebox cell setfront " + id + "</yellow>.");
        return Command.SINGLE_SUCCESS;
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
        if (cells.overlapsAny(world.getName(), front, cell)) {
            throw fail("That selection overlaps another cell.");
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
            // Everything in the front space belongs to the owner and is cleared into their impound later.
            throw fail(blocks + " block(s) are in the selection. The front space must be empty: select only the air in front of the cell.");
        }

        cells.setFront(cell, front, facing);
        player.sendRichMessage("<green>Cell <white>" + cell.getId() + "</white> is ready: its front faces " + facing.name().toLowerCase()
            + " and its starting room is carved. Regions: <white>" + cell.getRoomRegionId() + "</white> and <white>" + cell.getFrontRegionId() + "</white>."
            + (cell.isFree() ? "" : " It was handed to a player waiting for a cell."));
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
        cells.removeCell(cell);
        ctx.getSource().getSender().sendRichMessage("<green>Deleted cell <white>" + cell.getId() + "</white> and its regions. Blocks were left as they are.");
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
