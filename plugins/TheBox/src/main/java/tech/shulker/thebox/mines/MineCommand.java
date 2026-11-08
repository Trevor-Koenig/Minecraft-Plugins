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
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.BlockType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** The admin command: /mine create|delete|list|reset|addblock|removeblock|setresettime|settp|reload. */
public class MineCommand {

    // Brigadier's way of failing a command: throw one of these and the player sees the message in red.
    private static final DynamicCommandExceptionType UNKNOWN_MINE =
        new DynamicCommandExceptionType(id -> new LiteralMessage("Unknown mine: " + id));
    private static final DynamicCommandExceptionType MINE_EXISTS =
        new DynamicCommandExceptionType(id -> new LiteralMessage("A mine called " + id + " already exists."));
    private static final SimpleCommandExceptionType INVALID_ID =
        new SimpleCommandExceptionType(new LiteralMessage("Mine ids may only use lowercase letters, numbers and _."));
    private static final SimpleCommandExceptionType NO_SELECTION =
        new SimpleCommandExceptionType(new LiteralMessage("Make a WorldEdit selection first (//wand, then click two corners)."));
    private static final SimpleCommandExceptionType LAST_BLOCK =
        new SimpleCommandExceptionType(new LiteralMessage("A mine needs at least one block. Add another before removing this one."));
    private static final SimpleCommandExceptionType BLOCK_NOT_IN_MINE =
        new SimpleCommandExceptionType(new LiteralMessage("That block isn't in this mine's mix."));
    private static final SimpleCommandExceptionType TELEPORT_INSIDE =
        new SimpleCommandExceptionType(new LiteralMessage("Stand outside the mine: a teleport point inside it would bury players on reset."));

    private final MineManager mines;

    public MineCommand(MineManager mines) {
        this.mines = mines;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("mine")
            // Only admins can use, or even see, /mine. Players use /mines instead.
            .requires(source -> source.getSender().hasPermission("thebox.admin"))
            .executes(this::help)
            .then(Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word())
                    .executes(this::create)))
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
            <yellow>/mine create \\<id></yellow> <gray>- create a mine from your WorldEdit selection</gray>
            <yellow>/mine list</yellow> <gray>- list all mines</gray>
            <yellow>/mine reset \\<mine></yellow> <gray>- refill a mine now</gray>
            <yellow>/mine addblock \\<mine> \\<block> \\<weight></yellow> <gray>- add or change a block in the mix</gray>
            <yellow>/mine removeblock \\<mine> \\<block></yellow> <gray>- remove a block from the mix</gray>
            <yellow>/mine setresettime \\<mine> \\<seconds></yellow> <gray>- automatic reset interval (0 = off)</gray>
            <yellow>/mine setname \\<mine> \\<name></yellow> <gray>- the name players see (MiniMessage, e.g. \\<gold>Gold Rush)</gray>
            <yellow>/mine setresetpercent \\<mine> \\<percent></yellow> <gray>- reset once this % is mined (0 = off)</gray>
            <yellow>/mine settp \\<mine></yellow> <gray>- set the teleport point to where you stand</gray>
            <yellow>/mine delete \\<mine></yellow> <gray>- delete a mine (blocks stay)</gray>
            <yellow>/mine reload</yellow> <gray>- re-read mines.yml</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        // Only players have a WorldEdit selection; the console gets vanilla's "a player is required" error.
        Player player = ctx.getSource().getPlayerOrThrow();

        String id = StringArgumentType.getString(ctx, "id");
        if (!MineManager.isValidId(id)) {
            throw INVALID_ID.create();
        }
        if (mines.get(id) != null) {
            throw MINE_EXISTS.create(id);
        }

        // Read the player's WorldEdit selection: Bukkit player -> WorldEdit player -> session -> selection.
        Region selection;
        try {
            BukkitPlayer worldEditPlayer = BukkitAdapter.adapt(player);
            selection = WorldEdit.getInstance().getSessionManager().get(worldEditPlayer)
                .getSelection(worldEditPlayer.getWorld());
        } catch (IncompleteRegionException e) {
            throw NO_SELECTION.create();
        }

        // Any selection shape works; the mine uses its bounding box.
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        Mine mine = new Mine(id, player.getWorld(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        mine.getBlocks().put(BlockType.STONE, 100);
        mines.add(mine);

        player.sendRichMessage("<green>Created mine <white>" + id + "</white> from "
            + min.x() + ", " + min.y() + ", " + min.z() + " to " + max.x() + ", " + max.y() + ", " + max.z() + ".</green>");
        player.sendRichMessage("<gray>It's all stone for now: use /mine addblock to set the mix, then /mine reset " + id + ".");
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Mine mine = getMine(ctx);
        mines.remove(mine);
        ctx.getSource().getSender().sendRichMessage("<green>Deleted mine <white>" + mine.getId() + "</white>. Its blocks were left as they are.");
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (mines.getAll().isEmpty()) {
            sender.sendRichMessage("<gray>No mines yet. Create one with /mine create \\<id>.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<gold>Mines:");
        for (Mine mine : mines.getAll()) {
            String reset = mine.getResetIntervalSeconds() > 0
                ? "every " + mine.getResetIntervalSeconds() + "s (next in " + mine.getSecondsUntilReset() + "s)"
                : "manual only";
            sender.sendRichMessage("<yellow>" + mine.getId() + "</yellow> <gray>- " + mine.getWorld().getName()
                + ", blocks: " + describeBlocks(mine) + ", reset: " + reset
                + (mine.getResetPercent() > 0
                    ? ", or at " + mine.getResetPercent() + "% mined (now " + mine.getPercentMined() + "%)"
                    : ""));
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
