// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PermissionNode;
import tech.shulker.thebox.cells.CellManager;
import tech.shulker.thebox.cells.CellTier;
import tech.shulker.thebox.market.MarketManager;
import tech.shulker.thebox.market.Marketplace;
import tech.shulker.thebox.mines.Mine;
import tech.shulker.thebox.mines.MineManager;

/**
 * What a rank unlocks, read from LuckPerms so it's never written down twice: the permissions set on the rank's own
 * group. Each rank group inherits the one below it, so its own permissions are exactly what's new at that rank. Only
 * permissions players would recognize are listed (TheBox's, EssentialsX kits and commands, PlayerParticles); anything
 * else is left out. The LuckPerms API is thread-safe and keeps every group in memory, so this never waits on storage.
 */
public class Unlocks {

    private final LuckPerms luckPerms;
    private final MineManager mines;
    private final MarketManager markets;
    private final CellManager cells;

    public Unlocks(LuckPerms luckPerms, MineManager mines, MarketManager markets, CellManager cells) {
        this.luckPerms = luckPerms;
        this.mines = mines;
        this.markets = markets;
        this.cells = cells;
    }

    /** The rank's unlocks, mines first and looks last, or an empty list if its group grants nothing we can name. */
    public List<Component> of(String rankId) {
        Group group = luckPerms.getGroupManager().getGroup(rankId);
        if (group == null) {
            return List.of();
        }
        // Permissions limited to another server or a world (LuckPerms contexts) don't count here.
        ImmutableContextSet here = luckPerms.getContextManager().getStaticContext();
        TreeMap<String, Component> lines = new TreeMap<>(); // sorted by kind, then permission: the same order every time
        for (PermissionNode node : group.getNodes(NodeType.PERMISSION)) {
            if (!node.getValue() || !node.getContexts().isSatisfiedBy(here)) {
                continue;
            }
            Line line = describe(node.getPermission());
            if (line != null) {
                lines.put(line.kind() + ":" + node.getPermission(), line.text());
            }
        }
        return List.copyOf(lines.values());
    }

    /** "Mine K, /feed, /kit k", with gray commas. */
    public static Component join(List<Component> unlocks) {
        return Component.join(JoinConfiguration.separator(Component.text(", ", NamedTextColor.GRAY)), unlocks);
    }

    // ---------------------------------------------------------------- naming permissions

    /** kind orders the list: 0 mines, 1 cells, 2 markets, 3 kits, 4 effects, 5 commands, 6 nicknames, 7 particles. */
    private record Line(int kind, Component text) {}

    private Line describe(String permission) {
        if (permission.endsWith("*")) {
            return null; // a wildcard is too broad to name
        }
        String rest;
        if ((rest = after(permission, "thebox.mine.")) != null) {
            Mine mine = mines.get(rest);
            return new Line(0, MiniMessage.miniMessage().deserialize("Mine <name>",
                Placeholder.parsed("name", mine != null ? mine.getDisplayName() : rest)));
        }
        if ((rest = after(permission, "thebox.cell.size.")) != null) {
            CellTier.Size size = cellSize(rest);
            return new Line(1, Component.text(size != null ? "Cell upgrade to " + size.floor() : "Cell size " + rest));
        }
        if ((rest = after(permission, "thebox.marketplace.")) != null) {
            Marketplace market = markets.get(rest);
            return new Line(2, MiniMessage.miniMessage().deserialize("Market <name>",
                Placeholder.parsed("name", market != null ? market.getDisplayName() : rest)));
        }
        if (permission.equals("thebox.market.rent")) {
            return new Line(2, Component.text("Renting a market stall"));
        }
        if ((rest = after(permission, "essentials.kits.")) != null) {
            String command = "/kit " + rest;
            return new Line(3, Component.text(command, NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to claim"))));
        }
        if ((rest = after(permission, "thebox.effect.")) != null) {
            return new Line(4, Component.text("Effect: " + effectName(rest)));
        }
        switch (permission) {
            case "essentials.nick.color" -> { return new Line(6, Component.text("Colored nickname")); }
            case "essentials.nick.rgb" -> { return new Line(6, Component.text("Any color in your nickname")); }
            case "essentials.nick.format" -> { return new Line(6, Component.text("Bold and italic nickname")); }
            default -> { }
        }
        // essentials.<command> is the permission for /<command>. essentials.kit only opens /kit; the kits are listed.
        if ((rest = after(permission, "essentials.")) != null && !rest.contains(".") && !rest.equals("kit")) {
            return new Line(5, Component.text("/" + rest, NamedTextColor.YELLOW));
        }
        if ((rest = after(permission, "playerparticles.effect.")) != null) {
            return new Line(7, Component.text("Particle: " + rest.replace('_', ' ')));
        }
        if ((rest = after(permission, "playerparticles.style.")) != null) {
            return new Line(7, Component.text("Particle style: " + rest.replace('_', ' ')));
        }
        if ((rest = after(permission, "playerparticles.particles.max.")) != null) {
            return new Line(7, Component.text("Up to " + rest + " particles at once"));
        }
        return null;
    }

    /** The part after the prefix, or null if the permission doesn't start with it (or is just the prefix). */
    private static String after(String permission, String prefix) {
        return permission.startsWith(prefix) && permission.length() > prefix.length() ? permission.substring(prefix.length()) : null;
    }

    /** Size n, counted across all tiers like thebox.cell.size.n, or null. */
    private CellTier.Size cellSize(String number) {
        int n;
        try {
            n = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return null;
        }
        int count = 0;
        for (CellTier tier : cells.getTiers()) {
            for (CellTier.Size size : tier.sizes()) {
                if (++count == n) {
                    return size;
                }
            }
        }
        return null;
    }

    /** "speed.2" becomes "Speed II", "night_vision" becomes "Night Vision" (the same names /effects uses). */
    private static String effectName(String rest) {
        String key = rest;
        String level = "";
        int dot = rest.lastIndexOf('.');
        if (dot > 0 && rest.substring(dot + 1).matches("\\d+")) {
            key = rest.substring(0, dot);
            level = switch (rest.substring(dot + 1)) {
                case "1" -> " I";
                case "2" -> " II";
                case "3" -> " III";
                case "4" -> " IV";
                case "5" -> " V";
                default -> " " + rest.substring(dot + 1);
            };
        }
        StringBuilder name = new StringBuilder();
        for (String word : key.split("_")) {
            if (!word.isEmpty()) {
                name.append(name.isEmpty() ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return name + level;
    }
}
