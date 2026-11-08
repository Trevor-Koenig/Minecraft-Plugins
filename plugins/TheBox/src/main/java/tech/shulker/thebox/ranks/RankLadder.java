// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import java.util.List;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.track.PromotionResult;
import net.luckperms.api.track.Track;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Players' places on the rank ladder, which LuckPerms stores: the ladder is the "ranks" track, and a
 * player's rank is their group on it. Everything here works on global (context-free) group
 * memberships, so ranks follow players across a network.
 */
public class RankLadder implements Listener {

    public static final String TRACK = "ranks";
    public static final String PRESTIGE_TRACK = "prestige";

    private final LuckPerms luckPerms;

    public RankLadder(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    /** The ranks track, or null if it hasn't been created in LuckPerms. Tracks are always in memory. */
    public Track getTrack() {
        return luckPerms.getTrackManager().getTrack(TRACK);
    }

    /** The prestige track (p1, p2, ...), or null if it hasn't been created in LuckPerms. */
    public Track getPrestigeTrack() {
        return luckPerms.getTrackManager().getTrack(PRESTIGE_TRACK);
    }

    /** The player's prestige level: their position on the prestige track (p1 = 1), or 0 if they're not on it. */
    public int getPrestige(User user) {
        Track prestigeTrack = getPrestigeTrack();
        if (prestigeTrack == null) {
            return 0;
        }
        String group = getCurrentGroup(user, prestigeTrack);
        return group == null ? 0 : prestigeTrack.getGroups().indexOf(group) + 1;
    }

    /**
     * Promotes the player one step on the prestige track and moves them back to the first rank, then saves.
     * A player with no prestige yet isn't on the prestige track; LuckPerms then adds them to its first
     * group (p1) and reports ADDED_TO_FIRST_GROUP, which counts as a success.
     */
    public PromotionResult prestige(User user, Track ranks, Track prestigeTrack) {
        PromotionResult result = prestigeTrack.promote(user, ImmutableContextSet.empty());
        if (!result.wasSuccessful()) {
            return result;
        }
        for (String group : ranks.getGroups()) {
            user.data().remove(InheritanceNode.builder(group).build());
        }
        user.data().add(InheritanceNode.builder(ranks.getGroups().getFirst()).build());
        luckPerms.getUserManager().saveUser(user);
        return result;
    }

    /** LuckPerms always has online players' data loaded, so this never waits on the database. */
    public User getUser(Player player) {
        return luckPerms.getPlayerAdapter(Player.class).getUser(player);
    }

    /** The player's rank: their highest group on the track, or null if they aren't on it. */
    public String getCurrentGroup(User user, Track track) {
        List<String> groups = track.getGroups();
        String current = null;
        for (InheritanceNode node : user.getNodes(NodeType.INHERITANCE)) {
            String group = node.getGroupName();
            if (node.getValue() && node.getContexts().isEmpty() && track.containsGroup(group)
                    && (current == null || groups.indexOf(group) > groups.indexOf(current))) {
                current = group;
            }
        }
        return current;
    }

    /**
     * Puts a player who isn't on the ladder yet into its first group (rank a), and saves.
     * New players are only in LuckPerms' "default" group, which LuckPerms advises against renaming or
     * replacing, so we add the first rank alongside it.
     */
    public void ensureOnLadder(User user, Track track) {
        if (track.getGroups().isEmpty() || getCurrentGroup(user, track) != null) {
            return;
        }
        user.data().add(InheritanceNode.builder(track.getGroups().getFirst()).build());
        luckPerms.getUserManager().saveUser(user);
    }

    /** Moves the player one group up the track and saves. The result says whether it worked. */
    public PromotionResult promote(User user, Track track) {
        PromotionResult result = track.promote(user, ImmutableContextSet.empty());
        if (result.wasSuccessful()) {
            luckPerms.getUserManager().saveUser(user);
        }
        return result;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Track track = getTrack();
        if (track != null) {
            ensureOnLadder(getUser(event.getPlayer()), track);
        }
    }
}
