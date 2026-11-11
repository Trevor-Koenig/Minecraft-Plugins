// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.cells;

import static tech.shulker.thebox.utils.Errors.fail;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import tech.shulker.thebox.market.ImpoundMenu;
import tech.shulker.thebox.market.MarketManager;
import tech.shulker.thebox.sell.VaultEconomy;
import tech.shulker.thebox.utils.Durations;

/**
 * The player command: /cell goes to your cell, and its subcommands visit, upgrade, trust helpers, and handle
 * moving to a bigger cell (old, release). Impounded items are claimed with /cell claim (the market's impound).
 */
public class CellCommand {

    private final CellManager cells;
    private final MarketManager market;
    private final Logger logger;

    public CellCommand(CellManager cells, MarketManager market, Logger logger) {
        this.cells = cells;
        this.market = market;
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("cell")
            .executes(this::home)
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("buy").executes(this::buy))
            .then(Commands.literal("info").executes(this::info))
            .then(Commands.literal("visit")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        for (Player online : ctx.getSource().getSender().getServer().getOnlinePlayers()) {
                            if (online.getName().toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                                builder.suggest(online.getName());
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(this::visit)))
            .then(Commands.literal("upgrade")
                .executes(this::upgradeMenu)
                .then(Commands.literal("confirm").executes(ctx -> {
                    upgrade(ctx.getSource().getPlayerOrThrow());
                    return Command.SINGLE_SUCCESS;
                })))
            .then(Commands.literal("trust")
                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::trust)))
            .then(Commands.literal("untrust")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        if (ctx.getSource().getSender() instanceof Player player) {
                            Cell cell = cells.currentCell(player.getUniqueId());
                            if (cell != null) {
                                for (UUID helper : cell.getHelpers()) {
                                    String name = player.getServer().getOfflinePlayer(helper).getName();
                                    if (name != null && name.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                                        builder.suggest(name);
                                    }
                                }
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(this::untrust)))
            .then(Commands.literal("old").executes(this::old))
            .then(Commands.literal("release")
                .executes(this::releaseInfo)
                .then(Commands.literal("confirm").executes(this::release)))
            .then(Commands.literal("claim").executes(this::claim))
            .build();
    }

    // ---------------------------------------------------------------- going places

    private int home(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        UUID uuid = player.getUniqueId();
        Cell cell = cells.currentCell(uuid);
        if (cell == null) {
            // No cell yet: this is how players get one (see CellManager.onJoin for why it isn't automatic).
            checkStarterCellFree(player);
            double price = cells.starterPrice();
            if (price > 0) {
                player.sendRichMessage("<gold>You don't have a cell yet.</gold> Your own cell costs <price>: type <yellow>/cell buy</yellow> to get it.",
                    Placeholder.unparsed("price", VaultEconomy.format(player, price)));
                return Command.SINGLE_SUCCESS;
            }
            cell = giveStarterCell(player, null, 0);
        }
        teleport(player, cells.home(cell));
        return Command.SINGLE_SUCCESS;
    }

    /** /cell buy: pays for a first cell (the price of tier 1's first size) and goes there. */
    private int buy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        if (cells.currentCell(player.getUniqueId()) != null) {
            throw fail("You already have a cell: /cell takes you there.");
        }
        // Check a cell is free before taking any money.
        checkStarterCellFree(player);
        double price = cells.starterPrice();
        Economy economy = null;
        if (price > 0) {
            economy = VaultEconomy.find(player.getServer());
            if (economy == null) {
                throw fail("Cells can't be bought right now: no economy plugin is installed.");
            }
            if (!economy.has(player, price)) {
                throw fail("Your own cell costs <price>. You have <balance>.",
                    Placeholder.unparsed("price", economy.format(price)), Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
            }
            if (!economy.withdrawPlayer(player, price).transactionSuccess()) {
                throw fail("The economy refused the payment.");
            }
        }
        Cell cell = giveStarterCell(player, economy, price);
        teleport(player, cells.home(cell));
        return Command.SINGLE_SUCCESS;
    }

    /** Fails (and tells the admins) if no starting cell is free. */
    private void checkStarterCellFree(Player player) throws CommandSyntaxException {
        if (!cells.hasFreeStarterCell()) {
            cells.warnAdmins(player.getName() + " wants a cell, but every starting cell is taken. Build and register more (/thebox cell create).");
            throw fail("Every cell is taken right now. The admins have been told; try again later.");
        }
    }

    /** Hands the player a free starting cell they've paid {@code price} for (refunded if, after all, none is free). */
    private Cell giveStarterCell(Player player, Economy economy, double price) throws CommandSyntaxException {
        Cell cell = cells.assignStarterCell(player.getUniqueId());
        if (cell == null) {
            if (economy != null && price > 0) {
                economy.depositPlayer(player, price);
            }
            throw fail("Every cell is taken right now. Try again later.");
        }
        logger.info(player.getName() + " got cell " + cell.getId() + (price > 0 ? " for " + price : "") + ".");
        player.sendRichMessage("<green>Welcome to your cell!</green> <gray><yellow>/cell</yellow> brings you back here, and "
            + "<yellow>/cell help</yellow> shows what else you can do.");
        return cell;
    }

    private int visit(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        String name = StringArgumentType.getString(ctx, "player");
        // Offline players' cells can be visited too. IfCached: never look a name up online.
        OfflinePlayer target = player.getServer().getOfflinePlayerIfCached(name);
        Cell cell = target == null ? null : cells.currentCell(target.getUniqueId());
        if (cell == null) {
            throw fail("<name> doesn't have a cell.", Placeholder.unparsed("name", name));
        }
        teleport(player, cell.visit());
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendRichMessage("<gray>You're at the front of your cell.");
        } else {
            player.sendRichMessage("<gray>You're outside <white><name></white>'s cell. Only they and their friends can open its door.",
                Placeholder.unparsed("name", target.getName() != null ? target.getName() : name));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int old(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell old = cells.oldCell(player.getUniqueId());
        if (old == null) {
            throw fail("You aren't moving out of a cell.");
        }
        teleport(player, cells.home(old));
        player.sendRichMessage("<gray>Your old cell. It's cleared in <time>; whatever's left then goes to your impound.",
            Placeholder.unparsed("time", Durations.format(old.getReleaseAt() - System.currentTimeMillis())));
        return Command.SINGLE_SUCCESS;
    }

    private static void teleport(Player player, Location location) throws CommandSyntaxException {
        if (location == null) {
            throw fail("That cell's world isn't loaded right now. Try again later.");
        }
        player.teleport(location);
    }

    // ---------------------------------------------------------------- information

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>Cells:</gold>
            <yellow>/cell</yellow> <gray>- go to your cell</gray>
            <yellow>/cell buy</yellow> <gray>- get your own cell, if you don't have one yet</gray>
            <yellow>/cell info</yellow> <gray>- your cell and its next upgrade</gray>
            <yellow>/cell upgrade</yellow> <gray>- make your cell bigger</gray>
            <yellow>/cell visit \\<player></yellow> <gray>- go to the front of someone's cell</gray>
            <yellow>/cell trust|untrust \\<player></yellow> <gray>- let a friend build in your cell</gray>
            <yellow>/cell old</yellow> <gray>- go back to your old cell while you move out</gray>
            <yellow>/cell release</yellow> <gray>- finish moving: clear your old cell now</gray>
            <yellow>/cell claim</yellow> <gray>- take back items from a cleared cell or stall</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell cell = ownCell(player);
        CellTier.Size size = cells.sizeOf(cell);
        List<String> helpers = cell.getHelpers().stream().map(helper -> nameOf(player, helper)).toList();
        player.sendRichMessage("<gold>Your cell:</gold> <gray>" + size.floor() + ", " + size.height() + " blocks tall, tier " + cell.getTier()
            + (helpers.isEmpty() ? "" : ". Helpers: <helpers>"), Placeholder.unparsed("helpers", String.join(", ", helpers)));
        CellManager.Upgrade next = cells.nextUpgrade(cell);
        if (next == null) {
            player.sendRichMessage("<green>Your cell is as big as it gets.");
        } else {
            player.sendRichMessage("<gray>Next: " + (next.move() ? "move to a " + next.size().floor() + " cell in tier " + next.tier().number()
                    : "grow to " + next.size().floor()) + " for <price>. "
                    + (player.hasPermission(next.permission()) ? "<green>/cell upgrade</green>" : "<red>Unlocks with a later rank.</red>"),
                Placeholder.unparsed("price", VaultEconomy.format(player, next.size().price())));
        }
        Cell old = cells.oldCell(player.getUniqueId());
        if (old != null) {
            player.sendRichMessage("<gold>Moving out: your old cell is cleared in " + Durations.format(old.getReleaseAt() - System.currentTimeMillis())
                + ". /cell old takes you there; /cell release clears it now.");
        }
        int waiting = market.getImpound().count(player.getUniqueId());
        if (waiting > 0) {
            player.sendRichMessage("<gold>" + waiting + " stack(s) are waiting in your impound: /cell claim");
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- upgrading

    private int upgradeMenu(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        ownCell(player);
        CellMenu.open(player, cells, this);
        return Command.SINGLE_SUCCESS;
    }

    /** Buys the player's next upgrade: a bigger room, or a move to the next tier. The menu calls this too. */
    public void upgrade(Player player) throws CommandSyntaxException {
        UUID uuid = player.getUniqueId();
        Cell cell = ownCell(player);
        CellManager.Upgrade next = cells.nextUpgrade(cell);
        if (next == null) {
            throw fail("Your cell is as big as it gets.");
        }
        if (!player.hasPermission(next.permission())) {
            throw fail("You haven't unlocked the next cell size yet: it comes with a later rank.");
        }
        if (cell.getWorld() == null) {
            throw fail("Your cell's world isn't loaded right now. Try again later.");
        }
        Cell target = null;
        if (next.move()) {
            if (cells.oldCell(uuid) != null) {
                throw fail("You're still moving out of your old cell. Finish with /cell release first.");
            }
            target = cells.freeCell(next.tier().number());
            if (target == null) {
                cells.warnAdmins(player.getName() + " wants to move to tier " + next.tier().number() + ", but no cell there is free. Build and register more.");
                throw fail("No tier " + next.tier().number() + " cell is free right now. The admins have been told.");
            }
        }
        Economy economy = VaultEconomy.find(player.getServer());
        if (economy == null) {
            throw fail("Upgrades are unavailable: no economy plugin is installed.");
        }
        double price = next.size().price();
        if (!economy.has(player, price)) {
            throw fail("That costs <price>. You have <balance>.",
                Placeholder.unparsed("price", economy.format(price)), Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
        }
        if (!economy.withdrawPlayer(player, price).transactionSuccess()) {
            throw fail("The economy refused the payment.");
        }

        if (next.move()) {
            cells.move(cell, target);
            logger.info(player.getName() + " moved from cell " + cell.getId() + " to " + target.getId() + " (tier " + target.getTier() + ") for " + price + ".");
            teleport(player, cells.home(target));
            player.sendRichMessage("<green>Welcome to your new " + next.size().floor() + " cell!</green> <gray>Your old one stays yours for "
                + Durations.format(cells.getMoveDays() * cells.getDayMillis()) + " so you can bring your things: <yellow>/cell old</yellow> takes you there. "
                + "Shulker boxes keep their contents when you break them. Anything you leave behind goes to your impound "
                + "(<yellow>/cell claim</yellow>), so nothing is lost. Done early? <yellow>/cell release</yellow>.");
        } else {
            cells.upgradeInPlace(cell);
            logger.info(player.getName() + " upgraded cell " + cell.getId() + " to " + next.size().floor() + " for " + price + ".");
            player.sendRichMessage("<green>Your cell is now " + next.size().floor() + " (<price>).", Placeholder.unparsed("price", economy.format(price)));
        }
    }

    // ---------------------------------------------------------------- helpers (trusted builders)

    private int trust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell cell = ownCell(player);
        List<Player> targets = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (targets.size() != 1) {
            throw fail("Name one online player.");
        }
        Player target = targets.getFirst();
        if (target.equals(player)) {
            throw fail("It's your cell: you can already build there.");
        }
        if (cell.getHelpers().contains(target.getUniqueId())) {
            throw fail("<name> can already build in your cell.", Placeholder.unparsed("name", target.getName()));
        }
        if (cell.getHelpers().size() >= cells.getMaxHelpers()) {
            throw fail("You can trust at most " + cells.getMaxHelpers() + " player(s). Untrust someone first.");
        }
        cells.addHelper(cell, target.getUniqueId());
        player.sendRichMessage("<green><name> can now build in your cell and open its door.", Placeholder.unparsed("name", target.getName()));
        target.sendRichMessage("<green><name> trusted you to build in their cell.", Placeholder.unparsed("name", player.getName()));
        return Command.SINGLE_SUCCESS;
    }

    private int untrust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell cell = ownCell(player);
        String name = StringArgumentType.getString(ctx, "player");
        for (UUID helper : cell.getHelpers()) {
            if (name.equalsIgnoreCase(player.getServer().getOfflinePlayer(helper).getName())) {
                cells.removeHelper(cell, helper);
                player.sendRichMessage("<green><name> can no longer build in your cell.", Placeholder.unparsed("name", name));
                return Command.SINGLE_SUCCESS;
            }
        }
        throw fail("<name> isn't trusted in your cell.", Placeholder.unparsed("name", name));
    }

    // ---------------------------------------------------------------- moving out, the impound

    private int releaseInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        if (cells.oldCell(player.getUniqueId()) == null) {
            throw fail("You aren't moving out of a cell.");
        }
        player.sendRichMessage("<gold>Releasing your old cell clears it right away.</gold> <gray>Everything still in it goes to your impound "
            + "(/cell claim). Run <yellow>/cell release confirm</yellow> to do it.");
        return Command.SINGLE_SUCCESS;
    }

    private int release(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Cell old = cells.oldCell(player.getUniqueId());
        if (old == null) {
            throw fail("You aren't moving out of a cell.");
        }
        int stacks = cells.release(old);
        if (stacks < 0) {
            throw fail("That cell's world isn't loaded right now. Try again later.");
        }
        logger.info(player.getName() + " released their old cell " + old.getId() + " (" + stacks + " stacks impounded).");
        player.sendRichMessage("<green>Your old cell is cleared." + (stacks > 0 ? " " + stacks + " stack(s) went to your impound: /cell claim" : ""));
        return Command.SINGLE_SUCCESS;
    }

    private int claim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        if (market.getImpound().count(player.getUniqueId()) == 0) {
            player.sendRichMessage("<gray>Your impound is empty.");
            return Command.SINGLE_SUCCESS;
        }
        ImpoundMenu.open(player, market, 0);
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers

    private Cell ownCell(Player player) throws CommandSyntaxException {
        Cell cell = cells.currentCell(player.getUniqueId());
        if (cell == null) {
            throw fail("You don't have a cell yet. /cell tells you how to get one.");
        }
        if (cells.sizeOf(cell) == null) {
            throw fail("Your cell's tier is missing from cells.yml. Tell an admin.");
        }
        return cell;
    }

    private static String nameOf(Player viewer, UUID uuid) {
        OfflinePlayer offline = viewer.getServer().getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString();
    }
}
