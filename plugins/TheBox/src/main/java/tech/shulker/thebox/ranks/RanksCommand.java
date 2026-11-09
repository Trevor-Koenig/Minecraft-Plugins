// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.model.user.User;
import net.luckperms.api.track.Track;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.sell.VaultEconomy;

/**
 * /ranks: the ladder. Players see which ranks they have and what the rest cost them (at their prestige level).
 * The console sees base costs. Admins also see each rank's id, for /thebox rank setname and setcost.
 */
public class RanksCommand {

    private final RankManager ranks;
    private final RankLadder ladder;

    public RanksCommand(RankManager ranks, RankLadder ladder) {
        this.ranks = ranks;
        this.ladder = ladder;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("ranks")
            .executes(this::list)
            .build();
    }

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
}
