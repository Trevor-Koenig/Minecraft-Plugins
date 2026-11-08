// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.utils;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.BukkitPlayer;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

/** Reads players' WorldEdit selections (used to create mines and market stalls). */
public class Selections {

    /**
     * The player's WorldEdit selection in their current world, or null if they haven't selected both corners.
     * Bukkit player -> WorldEdit player -> their session -> the selection.
     */
    public static Region get(Player player) {
        try {
            BukkitPlayer worldEditPlayer = BukkitAdapter.adapt(player);
            return WorldEdit.getInstance().getSessionManager().get(worldEditPlayer).getSelection(worldEditPlayer.getWorld());
        } catch (IncompleteRegionException e) {
            return null;
        }
    }
}
