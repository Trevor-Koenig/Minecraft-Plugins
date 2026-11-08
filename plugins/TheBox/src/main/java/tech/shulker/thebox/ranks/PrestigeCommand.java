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
import org.bukkit.entity.Player;

/**
 * The player command: /prestige explains what prestiging does, /prestige confirm does it.
 * Prestiging is only possible at the top rank. It moves the player back to the first rank and up one
 * prestige level, and later rank-ups cost more.
 */
public class PrestigeCommand {

    private static final DynamicCommandExceptionType FAILED = new DynamicCommandExceptionType(message -> (Message) message);

    private final RankManager ranks;
    private final RankLadder ladder;
    private final Logger logger;

    public PrestigeCommand(RankManager ranks, RankLadder ladder, Logger logger) {
        this.ranks = ranks;
        this.ladder = ladder;
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("prestige")
            .executes(this::info)
            .then(Commands.literal("confirm")
                .executes(this::confirm))
            .build();
    }

    // ---------------------------------------------------------------- executors

    /** Explains what prestiging would do, or what the player still needs. Doesn't prestige. */
    private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        User user = ladder.getUser(player);
        Track rankTrack = requireTrack(ladder.getTrack(), RankLadder.TRACK);
        Track prestigeTrack = requireTrack(ladder.getPrestigeTrack(), RankLadder.PRESTIGE_TRACK);
        int prestige = ladder.getPrestige(user);
        checkCanPrestige(user, rankTrack, prestigeTrack, prestige);

        player.sendRichMessage("""
            <gold>You can prestige!</gold> Prestiging:
            <gray>-</gray> moves you back to rank <first><gray>,</gray>
            <gray>-</gray> makes you <green>Prestige <next></green><gray>,</gray>
            <gray>-</gray> makes ranks cost <percent>% of their base price.
            Run <yellow>/prestige confirm</yellow> to do it.""",
            Placeholder.parsed("first", ranks.getDisplayName(rankTrack.getGroups().getFirst())),
            Placeholder.unparsed("next", String.valueOf(prestige + 1)),
            Placeholder.unparsed("percent", String.valueOf(Math.round(100 * (1 + ranks.getPrestigeMultiplier() * (prestige + 1))))));
        return Command.SINGLE_SUCCESS;
    }

    private int confirm(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        User user = ladder.getUser(player);
        Track rankTrack = requireTrack(ladder.getTrack(), RankLadder.TRACK);
        Track prestigeTrack = requireTrack(ladder.getPrestigeTrack(), RankLadder.PRESTIGE_TRACK);
        int prestige = ladder.getPrestige(user);
        checkCanPrestige(user, rankTrack, prestigeTrack, prestige);

        PromotionResult result = ladder.prestige(user, rankTrack, prestigeTrack);
        if (!result.wasSuccessful()) {
            logger.warning("Prestige of " + player.getName() + " failed (" + result.getStatus() + ").");
            throw fail("Something went wrong while prestiging (" + result.getStatus() + "). Nothing was changed. Tell an admin.");
        }

        int newPrestige = prestige + 1;
        String firstRank = ranks.getDisplayName(rankTrack.getGroups().getFirst());
        logger.info(player.getName() + " prestiged to prestige " + newPrestige + ".");
        player.sendRichMessage("<green>You are now <gold>Prestige <level></gold>, back at rank </green><first><green>. Good luck!",
            Placeholder.unparsed("level", String.valueOf(newPrestige)),
            Placeholder.parsed("first", firstRank));
        player.getServer().broadcast(MiniMessage.miniMessage().deserialize(
            "<gold><player></gold> <gray>reached</gray> <light_purple>Prestige <level></light_purple><gray>!",
            Placeholder.component("player", player.name()),
            Placeholder.unparsed("level", String.valueOf(newPrestige))));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- checks

    /** Throws an error explaining why the player can't prestige yet, if they can't. */
    private void checkCanPrestige(User user, Track rankTrack, Track prestigeTrack, int prestige) throws CommandSyntaxException {
        if (prestige >= prestigeTrack.getGroups().size()) {
            throw fail("You're at the highest prestige there is!");
        }
        ladder.ensureOnLadder(user, rankTrack);
        List<String> groups = rankTrack.getGroups();
        String current = ladder.getCurrentGroup(user, rankTrack);
        String top = groups.getLast();
        if (!top.equals(current)) {
            throw fail("You can prestige once you reach rank <top><red>. You're at rank <current><red>.",
                Placeholder.parsed("top", ranks.getDisplayName(top)),
                Placeholder.parsed("current", ranks.getDisplayName(current)));
        }
    }

    private static Track requireTrack(Track track, String name) throws CommandSyntaxException {
        if (track == null || track.getGroups().isEmpty()) {
            throw fail("Prestige isn't set up yet (the LuckPerms track '" + name + "' is missing or empty). Tell an admin.");
        }
        return track;
    }

    /** A red error message, built with MiniMessage. */
    private static CommandSyntaxException fail(String template, TagResolver... placeholders) {
        return FAILED.create(MessageComponentSerializer.message().serialize(
            MiniMessage.miniMessage().deserialize(template, placeholders)));
    }
}
