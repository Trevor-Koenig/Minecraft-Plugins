// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.effects;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import tech.shulker.thebox.menu.Menu;
import tech.shulker.thebox.utils.Errors;

/** /effects opens a chest menu to switch rank effects on and off; /effects (effect) does it without the menu. */
public class EffectsCommand {

    private final EffectPerks effects;

    public EffectsCommand(EffectPerks effects) {
        this.effects = effects;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("effects")
            .executes(ctx -> {
                openMenu(ctx.getSource().getPlayerOrThrow());
                return Command.SINGLE_SUCCESS;
            })
            .then(Commands.argument("effect", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (EffectPerks.Perk perk : EffectPerks.PERKS) {
                        if (perk.key().startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(perk.key());
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(this::toggle))
            .build();
    }

    private int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        String key = StringArgumentType.getString(ctx, "effect");
        EffectPerks.Perk perk = EffectPerks.find(key);
        if (perk == null) {
            throw Errors.fail("There's no effect called '<key>'. /effects lists them.", Placeholder.unparsed("key", key));
        }
        int level = effects.unlockedLevel(player, perk);
        if (level == 0) {
            throw Errors.fail("You haven't unlocked <name> yet. It comes with a later rank.", Placeholder.unparsed("name", perk.name(1)));
        }
        boolean on = effects.toggle(player, perk);
        player.sendRichMessage(on ? "<green><name> is on." : "<gray><name> is off.", Placeholder.unparsed("name", perk.name(level)));
        return Command.SINGLE_SUCCESS;
    }

    /** One button per effect, centered in the middle row with a gap between each. Locked effects show what's to come. */
    private void openMenu(Player player) {
        List<EffectPerks.Perk> perks = EffectPerks.PERKS;
        Menu menu = new Menu(player.getServer(), 3, Component.text("Your effects"));
        int first = 13 - (perks.size() - 1);
        for (int i = 0; i < perks.size(); i++) {
            EffectPerks.Perk perk = perks.get(i);
            int slot = first + 2 * i;
            int level = effects.unlockedLevel(player, perk);
            if (level == 0) {
                menu.setItem(slot, Menu.icon(perk.icon(), Component.text(perk.name(1), NamedTextColor.GRAY),
                    List.of(Component.text("Unlocks with a later rank", NamedTextColor.RED))));
                continue;
            }
            boolean on = effects.isOn(player, perk);
            List<Component> lore = new ArrayList<>();
            lore.add(on ? Component.text("On", NamedTextColor.GREEN) : Component.text("Off", NamedTextColor.GRAY));
            if (level < perk.maxLevel()) {
                lore.add(Component.text(perk.name(level + 1) + " unlocks with a later rank", NamedTextColor.GRAY));
            }
            lore.add(Component.text(on ? "Click to turn it off" : "Click to turn it on", NamedTextColor.YELLOW));
            ItemStack icon = Menu.icon(perk.icon(), Component.text(perk.name(level), on ? NamedTextColor.GREEN : NamedTextColor.GRAY), lore);
            if (on) {
                icon.editMeta(meta -> meta.setEnchantmentGlintOverride(true)); // the enchanted shimmer marks what's on
            }
            menu.setButton(slot, icon, clicker -> {
                effects.toggle(clicker, perk);
                openMenu(clicker); // redraw with the new state
            });
        }
        menu.open(player);
    }
}
