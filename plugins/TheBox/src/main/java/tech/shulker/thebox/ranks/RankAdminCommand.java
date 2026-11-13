// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import tech.shulker.thebox.sell.VaultEconomy;

/** The admin command: /thebox rank setname|setcost|setprestigemultiplier|reload. Mounted under /thebox, which checks thebox.admin. */
public class RankAdminCommand {

    private static final DynamicCommandExceptionType UNKNOWN_RANK =
        new DynamicCommandExceptionType(id -> new LiteralMessage("Unknown rank: " + id));

    private final RankManager ranks;

    public RankAdminCommand(RankManager ranks) {
        this.ranks = ranks;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("rank")
            .executes(this::help)
            .then(Commands.literal("setname")
                .then(rankArgument()
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(this::setName))))
            .then(Commands.literal("setcost")
                .then(rankArgument()
                    .then(Commands.argument("cost", DoubleArgumentType.doubleArg(0))
                        .executes(this::setCost))))
            .then(Commands.literal("setprestigemultiplier")
                .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0))
                    .executes(this::setPrestigeMultiplier)))
            .then(Commands.literal("reload")
                .executes(this::reload))
            .build();
    }

    // ---------------------------------------------------------------- arguments

    /** A <rank> argument that tab-completes the ranks in ranks.yml. */
    private RequiredArgumentBuilder<CommandSourceStack, String> rankArgument() {
        return Commands.argument("rank", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                for (Rank rank : ranks.getAll()) {
                    if (rank.getId().startsWith(builder.getRemainingLowerCase())) {
                        builder.suggest(rank.getId());
                    }
                }
                return builder.buildFuture();
            });
    }

    private Rank getRank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "rank");
        Rank rank = ranks.get(id);
        if (rank == null) {
            throw UNKNOWN_RANK.create(id);
        }
        return rank;
    }

    // ---------------------------------------------------------------- executors

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>TheBox rank commands:</gold> <gray>(the ladder itself: /rankup list, which shows admins the rank ids)</gray>
            <yellow>/thebox rank setname \\<rank> \\<name></yellow> <gray>- the name players see (MiniMessage, e.g. \\<light_purple>Shulker)</gray>
            <yellow>/thebox rank setcost \\<rank> \\<cost></yellow> <gray>- what ranking up to it costs</gray>
            <yellow>/thebox rank setprestigemultiplier \\<multiplier></yellow> <gray>- extra cost per prestige level (0.5 = +50%)</gray>
            <yellow>/thebox rank reload</yellow> <gray>- re-read ranks.yml</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int setName(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Rank rank = getRank(ctx);
        // MiniMessage on purpose, as with mine names: only admins can set it.
        rank.setDisplayName(StringArgumentType.getString(ctx, "name"));
        ranks.save();
        ctx.getSource().getSender().sendRichMessage("<green>Rank <white>" + rank.getId() + "</white> is now shown to players as: </green><name>",
            Placeholder.parsed("name", rank.getDisplayName()));
        return Command.SINGLE_SUCCESS;
    }

    private int setCost(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Rank rank = getRank(ctx);
        double cost = DoubleArgumentType.getDouble(ctx, "cost");
        rank.setCost(cost);
        ranks.save();
        CommandSender sender = ctx.getSource().getSender();
        sender.sendRichMessage("<green>Ranking up to <name><green> now costs <cost>.",
            Placeholder.parsed("name", rank.getDisplayName()),
            Placeholder.unparsed("cost", VaultEconomy.format(sender, cost)));
        if (ranks.isFirst(rank)) {
            sender.sendRichMessage("<gray>Note: players start at this rank, so this cost is never charged.");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int setPrestigeMultiplier(CommandContext<CommandSourceStack> ctx) {
        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        ranks.setPrestigeMultiplier(multiplier);
        ranks.save();
        ctx.getSource().getSender().sendRichMessage("<green>Each prestige level now adds " + Math.round(multiplier * 100)
            + "% to rank costs (prestige 2 pays " + Math.round(100 * (1 + 2 * multiplier)) + "% of the base cost).");
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        ranks.load();
        ctx.getSource().getSender().sendRichMessage("<green>Reloaded ranks.yml: " + ranks.getAll().size() + " rank(s).");
        return Command.SINGLE_SUCCESS;
    }
}
