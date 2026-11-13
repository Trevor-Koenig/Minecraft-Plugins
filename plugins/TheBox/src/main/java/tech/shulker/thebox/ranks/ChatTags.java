// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.model.user.User;
import net.luckperms.api.track.Track;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Formats chat as "[P2] [C] Name: message": the prestige tag from the first prestige on, then the rank's display
 * name. Paper fires AsyncChatEvent off the main thread, so this avoids the Bukkit API: LuckPerms is thread-safe,
 * and ranks.yml's names are only read (a message sent during /thebox rank reload may show the rank's letter).
 */
public class ChatTags implements Listener {

    private final RankManager ranks;
    private final RankLadder ladder;

    public ChatTags(RankManager ranks, RankLadder ladder) {
        this.ranks = ranks;
        this.ladder = ladder;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Component tags = tagsFor(event.getPlayer());
        // viewerUnaware: every viewer sees the same line, so Paper renders it once, not once per player.
        // sourceDisplayName is the player's display name, so EssentialsX nicknames still show.
        event.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, message) ->
            MiniMessage.miniMessage().deserialize("<tags><name><gray>:</gray> <message>",
                Placeholder.component("tags", tags),
                Placeholder.component("name", sourceDisplayName),
                Placeholder.component("message", message))));
    }

    /** "[P2] [C] ", "[C] " before the first prestige, or nothing for a player who isn't on the ladder. */
    private Component tagsFor(Player player) {
        User user = ladder.getUser(player);
        Component tags = Component.empty();
        int prestige = ladder.getPrestige(user);
        if (prestige > 0) {
            tags = tags.append(MiniMessage.miniMessage().deserialize("<gold>[P" + prestige + "]</gold> "));
        }
        Track track = ladder.getTrack();
        String rank = track == null ? null : ladder.getCurrentGroup(user, track);
        if (rank != null) {
            // Placeholder.parsed keeps the display name's own colors inside the brackets.
            tags = tags.append(MiniMessage.miniMessage().deserialize("<gray>[</gray><rank><gray>]</gray> ",
                Placeholder.parsed("rank", ranks.getDisplayName(rank))));
        }
        return tags;
    }
}
