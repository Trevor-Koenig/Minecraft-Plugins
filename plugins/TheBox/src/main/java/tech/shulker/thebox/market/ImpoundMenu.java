// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import tech.shulker.thebox.menu.Menu;

/** The /market claim chest menu: a player's impounded items, 45 per page. Clicking one takes it. */
public class ImpoundMenu {

    private static final int PAGE_SIZE = 45; // five rows of items, plus a row of buttons

    public static void open(Player player, MarketManager market, int page) {
        List<Impound.Entry> entries = market.getImpound().get(player.getUniqueId());
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.clamp(page, 0, pages - 1);

        Menu menu = new Menu(player.getServer(), 6,
            MiniMessage.miniMessage().deserialize("Impound <gray>(" + (current + 1) + "/" + pages + ")"));
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = current * PAGE_SIZE + slot;
            if (index >= entries.size()) {
                break;
            }
            Impound.Entry entry = entries.get(index);
            // Each button holds on to its own entry, so a click always takes the item that was shown.
            menu.setButton(slot, entry.item().clone(), clicker -> take(clicker, market, entry, current));
        }
        if (current > 0) {
            menu.setButton(45, Menu.icon(ItemType.ARROW, Component.text("Previous page"), List.of()),
                clicker -> open(clicker, market, current - 1));
        }
        menu.setItem(49, Menu.icon(ItemType.PAPER, Component.text("Your impound", NamedTextColor.GOLD), List.of(
            Component.text("Things from a market stall you lost or gave up.", NamedTextColor.GRAY),
            Component.text("Click an item to take it.", NamedTextColor.GRAY),
            Component.text("Unclaimed items are sold after " + market.getImpoundExpiryDays() + " days.", NamedTextColor.GRAY))));
        if (current < pages - 1) {
            menu.setButton(53, Menu.icon(ItemType.ARROW, Component.text("Next page"), List.of()),
                clicker -> open(clicker, market, current + 1));
        }
        menu.open(player);
    }

    /** Moves one stack from the impound to the player's inventory; whatever doesn't fit goes back. */
    private static void take(Player player, MarketManager market, Impound.Entry entry, int page) {
        Impound impound = market.getImpound();
        // Remove first, give second: if the entry is already gone (a double click), nothing is given.
        if (impound.remove(player.getUniqueId(), entry)) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(entry.item().clone());
            if (!leftover.isEmpty()) {
                impound.putBack(player.getUniqueId(), entry, leftover.values());
                player.sendRichMessage("<red>Your inventory is full.");
            }
        }
        open(player, market, page); // refresh
    }
}
