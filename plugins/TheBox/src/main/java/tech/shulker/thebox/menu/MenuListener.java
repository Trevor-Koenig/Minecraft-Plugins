// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.menu;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Makes Menus work: no item ever moves in or out of a menu, and clicking a button runs its action. */
public class MenuListener implements Listener {

    private final JavaPlugin plugin;

    public MenuListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // getInventory() is the top inventory of the open view, so this also catches clicks in the player's
        // own inventory while a menu is open (shift-clicking an item into the menu, say).
        // getHolder(false) skips taking a block-state snapshot, which Paper's docs recommend for this check.
        if (!(event.getInventory().getHolder(false) instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != menu.getInventory() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Consumer<Player> action = menu.getAction(event.getSlot());
        if (action != null) {
            // Bukkit forbids opening or closing inventories inside InventoryClickEvent (it's in the middle of
            // changing one), and actions often do that, so run them on the next tick.
            plugin.getServer().getScheduler().runTask(plugin, () -> action.accept(player));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof Menu) {
            event.setCancelled(true);
        }
    }
}
