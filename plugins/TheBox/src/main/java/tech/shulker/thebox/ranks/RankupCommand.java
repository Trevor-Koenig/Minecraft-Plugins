// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.List;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.model.user.User;
import net.luckperms.api.track.PromotionResult;
import net.luckperms.api.track.Track;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.sell.VaultEconomy;

/**
 * The player command: /rankup buys the next rank, /rankup max buys as many as the player can afford,
 * /rankup list shows the ladder, and /rankup help lists these.
 */
public class RankupCommand {

    // One exception type for every rank-up error. The message is built per error with MiniMessage, so
    // errors can show rank display names and money in the player's currency format.
    private static final DynamicCommandExceptionType FAILED = new DynamicCommandExceptionType(message -> (Message) message);

    private final RankManager ranks;
    private final RankLadder ladder;
    private final Logger logger;

    public RankupCommand(RankManager ranks, RankLadder ladder, Logger logger) {
        this.ranks = ranks;
        this.ladder = ladder;
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("rankup")
            .executes(this::rankup)
            .then(Commands.literal("max")
                .executes(this::rankupMax))
            .then(Commands.literal("list")
                .executes(this::list))
            .then(Commands.literal("help")
                .executes(this::help))
            .build();
    }

    // ---------------------------------------------------------------- executors

    private int rankup(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Rank rank = rankUpOnce(player);
        announce(player, rank);
        return Command.SINGLE_SUCCESS;
    }

    private int rankupMax(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        // The first rank-up reports its error as usual (top rank, not enough money, ...).
        Rank rank = rankUpOnce(player);
        int count = 1;
        // After that, keep going until something stops us; that's the normal way for "max" to end.
        while (true) {
            try {
                rank = rankUpOnce(player);
                count++;
            } catch (CommandSyntaxException stop) {
                break;
            }
        }
        if (count > 1) {
            player.sendRichMessage("<gray>Ranked up " + count + " times.");
        }
        announce(player, rank);
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- ranking up

    /** Charges the player for the next rank and promotes them to it. Returns the new rank. */
    private Rank rankUpOnce(Player player) throws CommandSyntaxException {
        Track track = ladder.getTrack();
        if (track == null || track.getGroups().isEmpty()) {
            throw fail("Ranks aren't set up yet (the LuckPerms track '" + RankLadder.TRACK + "' is missing or empty). Tell an admin.");
        }
        Economy economy = VaultEconomy.find(player.getServer());
        if (economy == null) {
            throw fail("Ranking up is unavailable: no economy plugin is installed.");
        }

        User user = ladder.getUser(player);
        ladder.ensureOnLadder(user, track); // normally done on join already
        List<String> groups = track.getGroups();
        int index = groups.indexOf(ladder.getCurrentGroup(user, track));
        if (index == groups.size() - 1) {
            throw fail("You're at the top rank! Use /prestige to start again with a prestige level.");
        }
        String nextId = groups.get(index + 1);
        Rank next = ranks.get(nextId);
        if (next == null) {
            throw fail("Rank " + nextId + " has no cost in ranks.yml yet. Tell an admin.");
        }

        double cost = ranks.getCost(next, ladder.getPrestige(user)); // more expensive at higher prestige
        if (!economy.has(player, cost)) {
            throw fail("You need <cost> to rank up to <rank><red>. You have <balance>.",
                Placeholder.unparsed("cost", economy.format(cost)),
                Placeholder.parsed("rank", next.getDisplayName()),
                Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
        }

        // Charge first, then promote. If the promotion fails, give the money back.
        EconomyResponse payment = economy.withdrawPlayer(player, cost);
        if (!payment.transactionSuccess()) {
            throw fail("The economy refused the payment: " + payment.errorMessage);
        }
        PromotionResult result = ladder.promote(user, track);
        if (!result.wasSuccessful()) {
            economy.depositPlayer(player, cost);
            logger.warning("Rank-up of " + player.getName() + " failed (" + result.getStatus() + "); refunded " + cost + ".");
            throw fail("Something went wrong while ranking up (" + result.getStatus() + "). You were refunded. Tell an admin.");
        }

        logger.info(player.getName() + " ranked up to " + nextId + " for " + cost + ".");
        return next;
    }

    // ---------------------------------------------------------------- the ladder

    /**
     * /rankup list: the ladder. Players see which ranks they have and what the rest cost them (at their prestige
     * level). The console sees base costs. Admins also see each rank's id, for /thebox rank setname and setcost.
     */
    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (ranks.getAll().isEmpty()) {
            sender.sendRichMessage("<gray>No ranks are set up yet.");
            return Command.SINGLE_SUCCESS;
        }

        // Where the player is: their index on the track, and their prestige (which raises costs).
        Track track = ladder.getTrack();
        List<String> groups = track == null ? List.of() : track.getGroups();
        int current = -1;
        int prestige = 0;
        if (sender instanceof Player player && track != null) {
            User user = ladder.getUser(player);
            current = groups.indexOf(ladder.getCurrentGroup(user, track));
            prestige = ladder.getPrestige(user);
        }

        int percent = (int) Math.round(ranks.getPrestigeMultiplier() * 100);
        if (!(sender instanceof Player)) {
            sender.sendRichMessage("<gold>Ranks:</gold> <gray>(base costs; each prestige level adds " + percent + "%)");
        } else if (prestige > 0) {
            sender.sendRichMessage("<gold>Ranks:</gold> <gray>(Prestige " + prestige + ": costs +" + percent * prestige + "%)");
        } else {
            sender.sendRichMessage("<gold>Ranks:");
        }

        boolean showIds = sender.hasPermission("thebox.admin");
        for (Rank rank : ranks.getAll()) {
            int position = groups.indexOf(rank.getId());
            String marker = position < 0 || current < 0 ? "<gray>•</gray>"
                : position < current ? "<green>✔</green>"
                : position == current ? "<gold>▶</gold>"
                : "<gray>•</gray>";
            String cost = position == current ? "<gold>you're here</gold>"
                : ranks.isFirst(rank) ? "starting rank"
                : VaultEconomy.format(sender, ranks.getCost(rank, prestige));
            sender.sendRichMessage(marker + " <name> <gray>- <cost>" + (showIds ? " <dark_gray>(" + rank.getId() + ")" : ""),
                Placeholder.parsed("name", rank.getDisplayName()),
                Placeholder.parsed("cost", cost));
        }

        if (current >= 0) {
            if (current == groups.size() - 1) {
                sender.sendRichMessage("<green>You're at the top rank! <yellow>/prestige</yellow> starts again with a prestige level.");
            } else {
                Rank next = ranks.get(groups.get(current + 1));
                if (next != null) {
                    sender.sendRichMessage("<gray>Next: </gray><name> <gray>for</gray> <yellow><cost></yellow><gray>. Use <yellow>/rankup</yellow>.",
                        Placeholder.parsed("name", next.getDisplayName()),
                        Placeholder.unparsed("cost", VaultEconomy.format(sender, ranks.getCost(next, prestige))));
                }
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>Ranks:</gold>
            <yellow>/rankup</yellow> <gray>- buy the next rank</gray>
            <yellow>/rankup max</yellow> <gray>- buy as many ranks as you can afford</gray>
            <yellow>/rankup list</yellow> <gray>- every rank, and what the rest cost you</gray>
            <yellow>/prestige</yellow> <gray>- at the top rank, start again with a prestige level</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers

    private void announce(Player player, Rank rank) {
        player.sendRichMessage("<green>You ranked up to </green><rank><green>!",
            Placeholder.parsed("rank", rank.getDisplayName()));
        player.getServer().broadcast(MiniMessage.miniMessage().deserialize(
            "<gold><player></gold> <gray>ranked up to</gray> <rank><gray>!",
            Placeholder.component("player", player.name()),
            Placeholder.parsed("rank", rank.getDisplayName())));
    }

    /** A red error message, built with MiniMessage. */
    private static CommandSyntaxException fail(String template, TagResolver... placeholders) {
        return FAILED.create(MessageComponentSerializer.message().serialize(
            MiniMessage.miniMessage().deserialize(template, placeholders)));
    }
}
