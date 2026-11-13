// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.sell.Earnings;

/**
 * TheBox's PlaceholderAPI placeholders, %thebox_...%. Only created when PlaceholderAPI is installed (TheBox's
 * onEnable checks first), so the server never needs PlaceholderAPI's classes otherwise.
 * <ul>
 *   <li>%thebox_earned%: lifetime /sell earnings in whole dollars. A plain number, so ajLeaderboards can rank it
 *       (/ajlb add %thebox_earned%). It works for offline players too, so /ajlb updatealloffline can fill a new
 *       board with everyone who has sold.</li>
 * </ul>
 */
public class TheBoxExpansion extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final Earnings earnings;

    public TheBoxExpansion(JavaPlugin plugin, Earnings earnings) {
        this.plugin = plugin;
        this.earnings = earnings;
    }

    @Override
    public String getIdentifier() {
        return "thebox";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /** An expansion inside a plugin must persist: otherwise /papi reload unregisters it until TheBox enables again. */
    @Override
    public boolean persist() {
        return true;
    }

    /** Called from whichever thread asks (hologram and leaderboard plugins often ask async); Earnings allows that. */
    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params.equalsIgnoreCase("earned")) {
            return player == null ? "" : String.valueOf((long) earnings.get(player.getUniqueId()));
        }
        return null; // not one of ours: PlaceholderAPI leaves the text as typed
    }
}
