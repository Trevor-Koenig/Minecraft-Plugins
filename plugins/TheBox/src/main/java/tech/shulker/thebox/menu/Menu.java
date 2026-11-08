// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;

/**
 * A chest menu: an inventory whose slots are buttons. Players can look but never move items in or out;
 * MenuListener cancels every click and runs the clicked slot's action instead.
 *
 * Being the inventory's InventoryHolder is how MenuListener recognizes TheBox's menus. That's Paper's
 * recommended way to identify your own inventories (titles aren't reliable).
 */
public class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public Menu(Server server, int rows, Component title) {
        this.inventory = server.createInventory(this, rows * 9, title);
    }

    /** Puts a button in a slot. The action runs on the next tick (see MenuListener). */
    public void setButton(int slot, ItemStack icon, Consumer<Player> action) {
        inventory.setItem(slot, icon);
        actions.put(slot, action);
    }

    /** Puts a display-only item in a slot. */
    public void setItem(int slot, ItemStack icon) {
        inventory.setItem(slot, icon);
        actions.remove(slot);
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    /** The action for a slot, or null. */
    Consumer<Player> getAction(int slot) {
        return actions.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** An icon: an item of the given type with a name and lore lines. */
    public static ItemStack icon(ItemType type, Component name, List<Component> lore) {
        ItemStack icon = type.createItemStack();
        icon.editMeta(meta -> {
            // itemName, unlike displayName, isn't shown in italics: it acts like the item's own name.
            meta.itemName(name);
            meta.lore(lore);
        });
        return icon;
    }
}
