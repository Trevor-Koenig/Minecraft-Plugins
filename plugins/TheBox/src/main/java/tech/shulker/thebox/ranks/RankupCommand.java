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
import org.bukkit.entity.Player;
import tech.shulker.thebox.sell.VaultEconomy;

/** The player command: /rankup buys the next rank, /rankup max buys as many as the player can afford. */
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
