// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.utils;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Teleports for player commands and menus. Player#teleport returns false when the PlayerTeleportEvent is
 * cancelled (by another plugin, or by TheBox's own mine lock), so these only let the caller say "you're
 * there" when the player actually moved.
 */
public final class Teleports {

    private static final String CANCELLED = "Your teleport was cancelled.";

    private Teleports() {
    }

    /** For menu buttons, which can't throw: teleports, or tells the player it was cancelled. Returns whether it happened. */
    public static boolean teleport(Player player, Location location) {
        if (player.teleport(location)) {
            return true;
        }
        player.sendRichMessage("<red>" + CANCELLED);
        return false;
    }

    /** For command executors: teleports, or throws a red error, which also stops the command before its next message. */
    public static void teleportOrFail(Player player, Location location) throws CommandSyntaxException {
        if (!player.teleport(location)) {
            throw Errors.fail(CANCELLED);
        }
    }
}
