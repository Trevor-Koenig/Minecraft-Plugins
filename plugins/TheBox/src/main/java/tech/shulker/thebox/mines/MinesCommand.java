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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** The player command: /mines lists the mines you've unlocked, /mines <mine> teleports you there. */
public class MinesCommand {

    // Same message whether the mine doesn't exist or is locked, so players can't probe for hidden mines.
    private static final SimpleCommandExceptionType NOT_AVAILABLE =
        new SimpleCommandExceptionType(new LiteralMessage("You haven't unlocked that mine."));

    private final MineManager mines;

    public MinesCommand(MineManager mines) {
        this.mines = mines;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("mines")
            .executes(this::list)
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

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<Mine> unlocked = unlockedMines(sender);
        if (unlocked.isEmpty()) {
            sender.sendRichMessage("<gray>You haven't unlocked any mines yet.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<gold>Your mines:</gold> <gray>(teleport with /mines \\<mine>)");
        for (Mine mine : unlocked) {
            sender.sendRichMessage("<yellow>- </yellow><name> <gray>(/mines " + mine.getId() + ")",
                Placeholder.parsed("name", mine.getDisplayName()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int teleport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Mine mine = mines.get(StringArgumentType.getString(ctx, "mine"));
        if (mine == null || !player.hasPermission(mine.getPermission())) {
            throw NOT_AVAILABLE.create();
        }
        player.teleport(mine.getTeleport());
        player.sendRichMessage("<green>Teleported to </green><name><green>.",
            Placeholder.parsed("name", mine.getDisplayName()));
        return Command.SINGLE_SUCCESS;
    }
}
