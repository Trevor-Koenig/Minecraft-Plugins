// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.mines;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.BlockType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.utils.Selections;

/** The admin command: /thebox mine create|delete|list|reset|addblock|removeblock|setresettime|settp|reload. */
public class MineAdminCommand {

    // Brigadier's way of failing a command: throw one of these and the player sees the message in red.
    private static final DynamicCommandExceptionType UNKNOWN_MINE =
        new DynamicCommandExceptionType(id -> new LiteralMessage("Unknown mine: " + id));
    private static final DynamicCommandExceptionType MINE_EXISTS =
        new DynamicCommandExceptionType(id -> new LiteralMessage("A mine called " + id + " already exists."));
    private static final SimpleCommandExceptionType INVALID_ID =
        new SimpleCommandExceptionType(new LiteralMessage("Mine ids may only use lowercase letters, numbers and _."));
    private static final DynamicCommandExceptionType RESERVED_ID =
        new DynamicCommandExceptionType(id -> new LiteralMessage("A mine can't be called " + id + ": /mine " + id + " is already a command."));
    private static final SimpleCommandExceptionType NO_SELECTION =
        new SimpleCommandExceptionType(new LiteralMessage("Make a WorldEdit selection first (//wand, then click two corners)."));
    private static final SimpleCommandExceptionType LAST_BLOCK =
        new SimpleCommandExceptionType(new LiteralMessage("A mine needs at least one block. Add another before removing this one."));
    private static final SimpleCommandExceptionType BLOCK_NOT_IN_MINE =
        new SimpleCommandExceptionType(new LiteralMessage("That block isn't in this mine's mix."));
    private static final SimpleCommandExceptionType TELEPORT_INSIDE =
        new SimpleCommandExceptionType(new LiteralMessage("Stand outside the mine: a teleport point inside it would bury players on reset."));
    private static final SimpleCommandExceptionType RESERVED_SUFFIX =
        new SimpleCommandExceptionType(new LiteralMessage("Mine ids can't end in " + Mine.AREA_REGION_SUFFIX
            + ": WorldGuard region mine_<id>" + Mine.AREA_REGION_SUFFIX + " is a mine's PvP area."));
    private static final DynamicCommandExceptionType OVERLAPS_MINE =
        new DynamicCommandExceptionType(id -> new LiteralMessage("Your selection overlaps mine " + id + ". Mines can't overlap: their resets would fight over the same blocks."));

    /** New mines reset on their own every hour. Percentage resets stay off until an admin sets one. */
    private static final int DEFAULT_RESET_SECONDS = 60 * 60;

    private final MineManager mines;

    public MineAdminCommand(MineManager mines) {
        this.mines = mines;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        // Mounted under /thebox, which checks thebox.admin for all admin commands. Players use /mine instead.
        return Commands.literal("mine")
            .executes(this::help)
            .then(Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(this::create)))
            .then(Commands.literal("rename")
                .then(mineArgument()
                    .then(Commands.argument("id", StringArgumentType.word()).executes(this::rename))))
            .then(Commands.literal("delete")
                .then(mineArgument().executes(this::delete)))
            .then(Commands.literal("list")
                .executes(this::list))
            .then(Commands.literal("reset")
                .then(mineArgument().executes(this::reset)))
            .then(Commands.literal("addblock")
                .then(mineArgument()
                    .then(Commands.argument("block", ArgumentTypes.resource(RegistryKey.BLOCK))
                        .then(Commands.argument("weight", IntegerArgumentType.integer(1))
                            .executes(this::addBlock)))))
            .then(Commands.literal("removeblock")
                .then(mineArgument()
                    .then(Commands.argument("block", ArgumentTypes.resource(RegistryKey.BLOCK))
                        .executes(this::removeBlock))))
            .then(Commands.literal("setresettime")
                .then(mineArgument()
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                        .executes(this::setResetTime))))
            .then(Commands.literal("setname")
                .then(mineArgument()
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(this::setName))))
            .then(Commands.literal("setresetpercent")
                .then(mineArgument()
                    .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                        .executes(this::setResetPercent))))
            .then(Commands.literal("setlevel")
                .then(mineArgument()
                    .then(Commands.argument("level", IntegerArgumentType.integer(0))
                        .executes(this::setLevel))))
            .then(Commands.literal("setsurface")
                .then(mineArgument()
                    .then(Commands.literal("auto").executes(ctx -> setSurface(ctx, null, false)))
                    .then(Commands.literal("none").executes(ctx -> setSurface(ctx, null, true)))
                    .then(Commands.argument("block", ArgumentTypes.resource(RegistryKey.BLOCK))
                        .executes(ctx -> setSurface(ctx, ctx.getArgument("block", BlockType.class), false)))))
            .then(Commands.literal("settp")
                .then(mineArgument().executes(this::setTeleport)))
            .then(Commands.literal("reload")
                .executes(this::reload))
            .build();
    }

    // ---------------------------------------------------------------- arguments

    /** A <mine> argument that tab-completes existing mine ids. */
    private RequiredArgumentBuilder<CommandSourceStack, String> mineArgument() {
        return Commands.argument("mine", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                for (Mine mine : mines.getAll()) {
                    if (mine.getId().startsWith(builder.getRemainingLowerCase())) {
                        builder.suggest(mine.getId());
                    }
                }
                return builder.buildFuture();
            });
    }

    private Mine getMine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "mine");
        Mine mine = mines.get(id);
        if (mine == null) {
            throw UNKNOWN_MINE.create(id);
        }
        return mine;
    }

    // ---------------------------------------------------------------- executors

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>TheBox mine commands:</gold>
            <yellow>/thebox mine create \\<id></yellow> <gray>- create a mine from your WorldEdit selection, with its WorldGuard regions</gray>
            <yellow>/thebox mine list</yellow> <gray>- list all mines</gray>
            <yellow>/thebox mine reset \\<mine></yellow> <gray>- refill a mine now</gray>
            <yellow>/thebox mine addblock \\<mine> \\<block> \\<weight></yellow> <gray>- add or change a block in the mix</gray>
            <yellow>/thebox mine removeblock \\<mine> \\<block></yellow> <gray>- remove a block from the mix</gray>
            <yellow>/thebox mine setresettime \\<mine> \\<seconds></yellow> <gray>- automatic reset interval (0 = off)</gray>
            <yellow>/thebox mine setname \\<mine> \\<name></yellow> <gray>- the name players see (MiniMessage, e.g. \\<gold>Gold Rush)</gray>
            <yellow>/thebox mine setresetpercent \\<mine> \\<percent></yellow> <gray>- reset (after a 10-second countdown) once this % is mined (0 = off)</gray>
            <yellow>/thebox mine setlevel \\<mine> \\<level></yellow> <gray>- PvP level: in and around a mine, players only fight at their own level (0 = no PvP)</gray>
            <yellow>/thebox mine setsurface \\<mine> \\<block>|auto|none</yellow> <gray>- the block on the faces players can see at each reset, so no ores show; auto (the default) is the mix's most common block</gray>
            <yellow>/thebox mine settp \\<mine></yellow> <gray>- set the teleport point to where you stand</gray>
            <yellow>/thebox mine rename \\<mine> \\<id></yellow> <gray>- change a mine's id, and so its permission</gray>
            <yellow>/thebox mine delete \\<mine></yellow> <gray>- delete a mine (blocks stay)</gray>
            <yellow>/thebox mine reload</yellow> <gray>- re-read mines.yml</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        // Only players have a WorldEdit selection; the console gets vanilla's "a player is required" error.
        Player player = ctx.getSource().getPlayerOrThrow();

        String id = StringArgumentType.getString(ctx, "id");
        if (!MineManager.isValidId(id)) {
            throw INVALID_ID.create();
        }
        if (MineCommand.SUBCOMMANDS.contains(id)) {
            throw RESERVED_ID.create(id);
        }
        if (id.endsWith(Mine.AREA_REGION_SUFFIX)) {
            throw RESERVED_SUFFIX.create();
        }
        if (mines.get(id) != null) {
            throw MINE_EXISTS.create(id);
        }

        Region selection = Selections.get(player);
        if (selection == null) {
            throw NO_SELECTION.create();
        }

        // Any selection shape works; the mine uses its bounding box.
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        Mine mine = new Mine(id, player.getWorld(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        for (Mine other : mines.getAll()) {
            if (other.getWorld().equals(mine.getWorld()) && other.getBox().overlaps(mine.getBox())) {
                throw OVERLAPS_MINE.create(other.getId());
            }
        }
        mine.getBlocks().put(BlockType.STONE, 100);
        mine.setResetIntervalSeconds(DEFAULT_RESET_SECONDS);
        mines.add(mine); // also creates its WorldGuard regions

        player.sendRichMessage("<green>Created mine <white>" + id + "</white> from "
            + min.x() + ", " + min.y() + ", " + min.z() + " to " + max.x() + ", " + max.y() + ", " + max.z() + ".</green>");
        player.sendRichMessage("<gray>Its WorldGuard regions are set up: <white>" + mine.getRegionId() + "</white> (players can break blocks, no mobs spawn) and <white>"
            + mine.getAreaRegionId() + "</white> (" + Mine.AREA_MARGIN + " blocks around it: PvP once it has a level). It resets every "
            + DEFAULT_RESET_SECONDS / 60 + " minutes.");
        player.sendRichMessage("<gray>Still to do: the mix (/thebox mine addblock, it's all stone for now, then /thebox mine reset " + id
            + "), its PvP level (/thebox mine setlevel " + id + " \\<level>), and the rank that unlocks it: <white>lp group \\<rank> permission set "
            + mine.getPermission() + " true");
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        mines.remove(mine);
        ctx.getSource().getSender().sendRichMessage("<green>Deleted mine <white>" + mine.getId() + "</white> and its WorldGuard regions. Its blocks were left as they are.");
        return Command.SINGLE_SUCCESS;
    }

    private int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        String oldId = mine.getId();
        String newId = StringArgumentType.getString(ctx, "id");
        if (!MineManager.isValidId(newId)) {
            throw INVALID_ID.create();
        }
        if (MineCommand.SUBCOMMANDS.contains(newId)) {
            throw RESERVED_ID.create(newId);
        }
        if (newId.endsWith(Mine.AREA_REGION_SUFFIX)) {
            throw RESERVED_SUFFIX.create();
        }
        if (!mines.rename(mine, newId)) {
            throw MINE_EXISTS.create(newId);
        }
        // LuckPerms grants still name the old permission. LuckPerms' bulk update renames a permission everywhere
        // (groups and users, keeping expiry and contexts), but only from the console, with a confirmation code.
        String update = "lp bulkupdate all update permission thebox.mine." + newId + " \"permission == thebox.mine." + oldId + "\"";
        ctx.getSource().getSender().sendRichMessage("<green>Renamed mine <white>" + oldId + "</white> to <white>" + newId + "</white>, and its WorldGuard regions with it. "
            + "<gray>Players now need <white>thebox.mine." + newId + "</white>. To move the existing grants, run this in the console "
            + "(click to copy): <click:copy_to_clipboard:'" + update + "'><yellow>" + update + "</yellow></click>");
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (mines.getAll().isEmpty()) {
            sender.sendRichMessage("<gray>No mines yet. Create one with /thebox mine create \\<id>.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<gold>Mines:");
        for (Mine mine : mines.getAll()) {
            String reset = mine.getResetIntervalSeconds() > 0
                ? "every " + mine.getResetIntervalSeconds() + "s (next in " + mine.getSecondsUntilReset() + "s)"
                : "manual only";
            sender.sendRichMessage("<yellow>" + mine.getId() + "</yellow> <gray>- " + mine.getWorld().getName()
                + ", blocks: " + describeBlocks(mine)
                + ", surface: " + describeSurface(mine)
                + ", reset: " + reset
                + (mine.getResetPercent() > 0
                    ? ", or at " + mine.getResetPercent() + "% mined (now " + mine.getPercentMined() + "%)"
                    : "")
                + ", level: " + (mine.getLevel() > 0 ? String.valueOf(mine.getLevel()) : "not set (no PvP)"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        mines.reset(mine);
        ctx.getSource().getSender().sendRichMessage("<green>Reset mine <white>" + mine.getId() + "</white>.");
        return Command.SINGLE_SUCCESS;
    }

    private int addBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        BlockType block = ctx.getArgument("block", BlockType.class);
        int weight = IntegerArgumentType.getInteger(ctx, "weight");
        mine.getBlocks().put(block, weight);
        mines.save();
        ctx.getSource().getSender().sendRichMessage("<green>Mine <white>" + mine.getId() + "</white> mix is now: " + describeBlocks(mine));
        return Command.SINGLE_SUCCESS;
    }

    private int removeBlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        BlockType block = ctx.getArgument("block", BlockType.class);
        if (!mine.getBlocks().containsKey(block)) {
            throw BLOCK_NOT_IN_MINE.create();
        }
        if (mine.getBlocks().size() == 1) {
            throw LAST_BLOCK.create();
        }
        mine.getBlocks().remove(block);
        mines.save();
        ctx.getSource().getSender().sendRichMessage("<green>Mine <white>" + mine.getId() + "</white> mix is now: " + describeBlocks(mine));
        return Command.SINGLE_SUCCESS;
    }

    private int setResetTime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        mine.setResetIntervalSeconds(seconds);
        mines.save();
        String message = seconds == 0
            ? "<green>Mine <white>" + mine.getId() + "</white> will now only reset manually."
            : "<green>Mine <white>" + mine.getId() + "</white> will reset every " + seconds + " seconds.";
        ctx.getSource().getSender().sendRichMessage(message);
        return Command.SINGLE_SUCCESS;
    }

    private int setName(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        // Display names are MiniMessage on purpose: only admins can set them, and they get colors this way.
        mine.setDisplayName(StringArgumentType.getString(ctx, "name"));
        mines.save();
        ctx.getSource().getSender().sendRichMessage("<green>Mine <white>" + mine.getId() + "</white> is now shown to players as: </green><name>",
            Placeholder.parsed("name", mine.getDisplayName()));
        return Command.SINGLE_SUCCESS;
    }

    private int setResetPercent(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        int percent = IntegerArgumentType.getInteger(ctx, "percent");
        mine.setResetPercent(percent);
        mines.save();
        String message = percent == 0
            ? "<green>Mine <white>" + mine.getId() + "</white> no longer resets based on how much is mined."
            : "<green>Mine <white>" + mine.getId() + "</white> will reset once " + percent + "% has been mined.";
        ctx.getSource().getSender().sendRichMessage(message);
        return Command.SINGLE_SUCCESS;
    }

    /** A block, or null with off false for the mix's most common block, or off true for no surface. */
    private int setSurface(CommandContext<CommandSourceStack> ctx, BlockType surface, boolean off) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        if (off) {
            mine.turnSurfaceOff();
        } else {
            mine.setSurface(surface);
        }
        mines.save();
        BlockType shown = mine.getSurfaceBlock();
        String resetHint = " Run <yellow>/thebox mine reset " + mine.getId() + "</yellow> to see it now.";
        CommandSender sender = ctx.getSource().getSender();
        if (off) {
            sender.sendRichMessage("<green>Mine <white>" + mine.getId() + "</white> has no surface now: its outside shows the mix." + resetHint);
        } else if (shown == null) {
            sender.sendRichMessage("<green>Mine <white>" + mine.getId() + "</white>'s surface will be its most common block, once it has blocks.");
        } else {
            sender.sendRichMessage("<green>From the next reset, mine <white>" + mine.getId() + "</white>'s visible faces are <white>"
                + shown.key().asString() + "</white>" + (surface == null ? " (its most common block)" : "") + "." + resetHint);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static String describeSurface(Mine mine) {
        BlockType block = mine.getSurfaceBlock();
        if (block == null) {
            return "none";
        }
        return block.key().value() + (mine.getSurface() == null ? " (most common)" : "");
    }

    private int setLevel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        int level = IntegerArgumentType.getInteger(ctx, "level");
        mines.setLevel(mine, level); // also turns PvP on or off in its area region
        CommandSender sender = ctx.getSource().getSender();
        if (level == 0) {
            sender.sendRichMessage("<green>Mine <white>" + mine.getId() + "</white> has no level now, so there's no PvP in or around it.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<green>Mine <white>" + mine.getId() + "</white> is now level " + level + ". Players fight in it, and within "
            + Mine.AREA_MARGIN + " blocks of it, only if " + level + " is the highest level among the mines they can enter.");
        List<String> overlapping = mines.regionsInArea(mine);
        if (!overlapping.isEmpty()) {
            sender.sendRichMessage("<yellow>Its PvP area overlaps " + String.join(", ", overlapping) + ", so players at level " + level
                + " can fight there too. Keep spawn, the market and cells " + Mine.AREA_MARGIN + " blocks away from mines.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setTeleport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Mine mine = getMine(ctx);
        Location location = player.getLocation();
        if (mine.contains(location)) {
            throw TELEPORT_INSIDE.create();
        }
        mine.setCustomTeleport(location);
        mines.save();
        player.sendRichMessage("<green>Teleport point for <white>" + mine.getId() + "</white> set to where you're standing.");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        mines.load();
        ctx.getSource().getSender().sendRichMessage("<green>Reloaded mines.yml: " + mines.getAll().size() + " mine(s).");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers

    /** "stone 70%, coal_ore 30%": each weight shown as a share of the total. */
    private static String describeBlocks(Mine mine) {
        int total = mine.getBlocks().values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder description = new StringBuilder();
        for (Map.Entry<BlockType, Integer> entry : mine.getBlocks().entrySet()) {
            if (!description.isEmpty()) {
                description.append(", ");
            }
            description.append(entry.getKey().key().value())
                .append(' ')
                .append(Math.round(entry.getValue() * 100.0 / total))
                .append('%');
        }
        return description.toString();
    }
}
