// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.mines;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.BlockType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemType;
import tech.shulker.thebox.menu.Menu;
import tech.shulker.thebox.utils.Teleports;

/**
 * The player command: /mine opens a menu of the mines you've unlocked (or goes straight to the only one),
 * /mine <mine> goes straight to one, and /mine help explains both.
 */
public class MineCommand {

    /**
     * Words /mine itself uses. Brigadier always prefers a matching literal over an argument, so a mine with one
     * of these ids could never be reached with /mine <id>; /thebox mine create and rename refuse them.
     */
    public static final Set<String> SUBCOMMANDS = Set.of("help");

    // Same message whether the mine doesn't exist or is locked, so players can't probe for hidden mines.
    private static final SimpleCommandExceptionType NOT_AVAILABLE =
        new SimpleCommandExceptionType(new LiteralMessage("You haven't unlocked that mine."));

    private final MineManager mines;

    public MineCommand(MineManager mines) {
        this.mines = mines;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("mine")
            .executes(this::menu)
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.argument("mine", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    // Only suggest mines this player can actually use.
                    for (Mine mine : unlockedMines(ctx.getSource().getSender())) {
                        if (mine.getId().startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(mine.getId());
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(this::teleport))
            .build();
    }

    private List<Mine> unlockedMines(CommandSender sender) {
        return mines.getAll().stream()
            .filter(mine -> sender.hasPermission(mine.getPermission()))
            .toList();
    }

    /** A menu of the player's unlocked mines; with only one, straight there. */
    private int menu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        List<Mine> unlocked = unlockedMines(player);
        if (unlocked.isEmpty()) {
            player.sendRichMessage("<gray>You haven't unlocked any mines yet.");
            return Command.SINGLE_SUCCESS;
        }
        if (unlocked.size() == 1) {
            goTo(player, unlocked.getFirst());
            return Command.SINGLE_SUCCESS;
        }
        int rows = Math.min(6, (unlocked.size() + 8) / 9);
        Menu menu = new Menu(player.getServer(), rows, Component.text("Mines"));
        for (int slot = 0; slot < unlocked.size() && slot < rows * 9; slot++) {
            Mine mine = unlocked.get(slot);
            menu.setButton(slot, Menu.icon(iconFor(mine), MiniMessage.miniMessage().deserialize(mine.getDisplayName()),
                    List.of(Component.text("Click to go there", NamedTextColor.GRAY))),
                clicker -> goTo(clicker, mine));
        }
        menu.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private int teleport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Mine mine = mines.get(StringArgumentType.getString(ctx, "mine"));
        if (mine == null || !player.hasPermission(mine.getPermission())) {
            throw NOT_AVAILABLE.create();
        }
        goTo(player, mine);
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>Mines:</gold>
            <yellow>/mine</yellow> <gray>- pick one of your mines to go to</gray>
            <yellow>/mine \\<mine></yellow> <gray>- go straight to one</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Teleports to a mine and says so, unless the teleport was cancelled. Menu buttons use it too, so it doesn't
     * throw. A button can't reach a mine the player has since lost: the mine lock also checks teleports.
     */
    private static void goTo(Player player, Mine mine) {
        if (Teleports.teleport(player, mine.getTeleport())) {
            player.sendRichMessage("<green>Teleported to </green><name><green>.",
                Placeholder.parsed("name", mine.getDisplayName()));
        }
    }

    /** A mine's menu icon: the block it shows on the outside (its surface), or a pickaxe if it has none. */
    private static ItemType iconFor(Mine mine) {
        BlockType surface = mine.getSurfaceBlock();
        return surface != null && surface.hasItemType() ? surface.getItemType() : ItemType.IRON_PICKAXE;
    }
}
