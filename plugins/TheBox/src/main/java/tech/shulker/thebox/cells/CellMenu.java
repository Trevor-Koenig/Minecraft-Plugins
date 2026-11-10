// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemType;
import tech.shulker.thebox.menu.Menu;
import tech.shulker.thebox.sell.VaultEconomy;

/** The /cell upgrade chest menu: the player's cell, and a button for its next upgrade. */
public class CellMenu {

    public static void open(Player player, CellManager cells, CellCommand command) {
        Cell cell = cells.currentCell(player.getUniqueId());
        CellTier.Size size = cell == null ? null : cells.sizeOf(cell);
        if (size == null) {
            player.sendRichMessage("<red>You don't have a cell yet.");
            return;
        }
        Menu menu = new Menu(player.getServer(), 3, Component.text("Your cell"));
        List<Component> about = new ArrayList<>(List.of(
            Component.text(size.height() + " blocks tall, tier " + cell.getTier(), NamedTextColor.GRAY)));
        if (!cell.getHelpers().isEmpty()) {
            about.add(Component.text(cell.getHelpers().size() + " helper(s)", NamedTextColor.GRAY));
        }
        menu.setItem(11, Menu.icon(ItemType.BRICKS, Component.text("Your cell: " + size.floor(), NamedTextColor.GOLD), about));

        CellManager.Upgrade next = cells.nextUpgrade(cell);
        if (next == null) {
            menu.setItem(15, Menu.icon(ItemType.NETHER_STAR, Component.text("As big as it gets", NamedTextColor.GREEN), List.of()));
        } else {
            boolean unlocked = player.hasPermission(next.permission());
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Price: " + VaultEconomy.format(player, next.size().price()), NamedTextColor.GRAY));
            if (next.move()) {
                lore.add(Component.text("A new cell in the tier " + next.tier().number() + " block.", NamedTextColor.GRAY));
                lore.add(Component.text("You keep your old one for " + cells.getMoveDays() + " day(s) to move your things.", NamedTextColor.GRAY));
            }
            lore.add(unlocked ? Component.text("Click to buy", NamedTextColor.GREEN) : Component.text("Unlocks with a later rank", NamedTextColor.RED));
            Component name = Component.text((next.move() ? "Move up to " : "Grow to ") + next.size().floor(), NamedTextColor.YELLOW);
            menu.setButton(15, Menu.icon(next.move() ? ItemType.SHULKER_BOX : ItemType.EMERALD, name, lore), clicker -> {
                clicker.closeInventory();
                try {
                    command.upgrade(clicker);
                } catch (CommandSyntaxException e) {
                    // Show the command's error as chat, in red like a command error.
                    clicker.sendMessage(MessageComponentSerializer.message().deserialize(e.getRawMessage()).colorIfAbsent(NamedTextColor.RED));
                }
            });
        }
        menu.open(player);
    }
}
