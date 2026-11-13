// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.themes;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.utils.Errors;

/** The admin command: /thebox theme list|preview|reload. Mounted under /thebox, which checks thebox.admin. */
public class ThemeAdminCommand {

    private final ThemeManager themes;

    public ThemeAdminCommand(ThemeManager themes) {
        this.themes = themes;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("theme")
            .executes(this::help)
            .then(Commands.literal("list")
                .executes(this::list))
            .then(Commands.literal("preview")
                .then(Commands.argument("theme", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (Theme theme : themes.getAll()) {
                            if (theme.id().startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(theme.id());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(this::preview)))
            .then(Commands.literal("reload")
                .executes(this::reload))
            .build();
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>TheBox theme commands:</gold>
            <yellow>/thebox theme list</yellow> <gray>- every theme, its PvP level and its ranks</gray>
            <yellow>/thebox theme preview \\<theme></yellow> <gray>- see its band title and a reset message</gray>
            <yellow>/thebox theme reload</yellow> <gray>- re-read themes.yml after editing it</gray>
            <gray>A mine uses the theme of its PvP level; </gray><yellow>/thebox mine settheme</yellow><gray> picks another.</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (themes.getAll().isEmpty()) {
            sender.sendRichMessage("<gray>No themes. Add some to themes.yml, or delete it and restart for the defaults.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<gold>Themes:");
        for (Theme theme : themes.getAll()) {
            String level = theme.level() >= 0 ? "level " + theme.level() + " mines" : "no level";
            String ranks = theme.ranks().isEmpty() ? "no ranks" : "ranks " + String.join(", ", theme.ranks());
            sender.sendRichMessage("<yellow>" + theme.id() + "</yellow> <gray>-</gray> <name> <gray>- " + level + ", " + ranks
                + ", " + theme.resetMessages().size() + " reset message(s)",
                Placeholder.parsed("name", theme.name()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int preview(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        String id = StringArgumentType.getString(ctx, "theme");
        Theme theme = themes.get(id);
        if (theme == null) {
            throw Errors.fail("There's no theme called '<id>'. /thebox theme list shows them.", Placeholder.unparsed("id", id));
        }
        themes.showTitle(player, theme);
        Component message = themes.resetMessage(theme, Component.text("Mine Q"));
        if (message != null) {
            player.sendMessage(message);
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        themes.load();
        ctx.getSource().getSender().sendRichMessage("<green>Reloaded themes.yml: " + themes.getAll().size() + " theme(s).");
        return Command.SINGLE_SUCCESS;
    }
}
