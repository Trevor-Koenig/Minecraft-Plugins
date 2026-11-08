// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import static tech.shulker.thebox.market.MarketManager.marketPlaceholder;
import static tech.shulker.thebox.market.MarketManager.stallPlaceholder;
import static tech.shulker.thebox.utils.Errors.fail;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemType;
import tech.shulker.thebox.menu.Menu;
import tech.shulker.thebox.sell.VaultEconomy;
import tech.shulker.thebox.utils.Durations;

/**
 * The player command: /market goes to the market (or picks one from a menu), and its subcommands rent and
 * manage a stall: rent, extend, info, autorenew, trust, untrust, unrent, claim.
 */
public class MarketCommand {

    private final MarketManager market;
    private final Logger logger;

    public MarketCommand(MarketManager market, Logger logger) {
        this.market = market;
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("market")
            .executes(this::go)
            .then(Commands.literal("help").executes(this::help))
            .then(Commands.literal("rent")
                .executes(ctx -> rent(ctx, 1))
                .then(Commands.argument("days", IntegerArgumentType.integer(1))
                    .executes(ctx -> rent(ctx, IntegerArgumentType.getInteger(ctx, "days")))))
            .then(Commands.literal("extend")
                .then(Commands.argument("days", IntegerArgumentType.integer(1))
                    .executes(this::extend)))
            .then(Commands.literal("info").executes(this::info))
            .then(Commands.literal("autorenew")
                .then(Commands.literal("on").executes(ctx -> autoRenew(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> autoRenew(ctx, false))))
            .then(Commands.literal("trust")
                .then(Commands.argument("player", ArgumentTypes.player())
                    .executes(this::trust)))
            .then(Commands.literal("untrust")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        if (ctx.getSource().getSender() instanceof Player player) {
                            for (Stall stall : market.getStallsRentedBy(player.getUniqueId())) {
                                for (UUID helper : stall.getHelpers()) {
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
            .then(Commands.literal("unrent")
                .executes(this::unrentInfo)
                .then(Commands.literal("confirm").executes(this::unrent)))
            .then(Commands.literal("claim").executes(this::claim))
            .build();
    }

    // ---------------------------------------------------------------- going to a market

    private int go(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        List<Marketplace> all = new ArrayList<>(market.getAll());
        if (all.isEmpty()) {
            throw fail("There's no market yet.");
        }
        if (all.size() == 1) {
            teleport(player, all.getFirst());
            return Command.SINGLE_SUCCESS;
        }
        // Several markets: let the player pick one from a chest menu.
        int rows = Math.min(6, (all.size() + 8) / 9);
        Menu menu = new Menu(player.getServer(), rows, Component.text("Markets"));
        for (int slot = 0; slot < all.size() && slot < rows * 9; slot++) {
            Marketplace m = all.get(slot);
            long free = m.getStalls().values().stream().filter(stall -> !stall.isRented()).count();
            menu.setButton(slot, Menu.icon(ItemType.CHEST, MiniMessage.miniMessage().deserialize(m.getDisplayName()), List.of(
                    Component.text(free + " of " + m.getStalls().size() + " stalls free", NamedTextColor.GRAY),
                    Component.text("Click to go there", NamedTextColor.GRAY))),
                clicker -> teleport(clicker, m));
        }
        menu.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private static void teleport(Player player, Marketplace m) {
        Location location = m.getTeleport() == null ? null : m.getTeleport().toLocation();
        if (location == null) {
            player.sendRichMessage("<red><market><red> isn't open yet.", marketPlaceholder(m));
            return;
        }
        player.teleport(location);
        player.sendRichMessage("<green>Welcome to </green><market><green>! Stand in a free stall and use /market info to see its rent.",
            marketPlaceholder(m));
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>Market:</gold>
            <yellow>/market</yellow> <gray>- go to the market</gray>
            <yellow>/market info</yellow> <gray>- your stalls, or the stall you're standing in</gray>
            <yellow>/market rent [days]</yellow> <gray>- rent the free stall you're standing in</gray>
            <yellow>/market extend \\<days></yellow> <gray>- pay rent ahead</gray>
            <yellow>/market autorenew on|off</yellow> <gray>- pay rent from your balance each day</gray>
            <yellow>/market trust|untrust \\<player></yellow> <gray>- let a friend build in your stall</gray>
            <yellow>/market unrent</yellow> <gray>- give up your stall</gray>
            <yellow>/market claim</yellow> <gray>- take back items from a cleared stall</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- renting

    private int rent(CommandContext<CommandSourceStack> ctx, int days) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        UUID uuid = player.getUniqueId();
        Stall stall = market.getStallAt(player.getLocation());
        if (stall == null) {
            throw fail("Stand inside a free stall to rent it. /market takes you to the market.");
        }
        Marketplace m = market.get(stall.getMarketId());
        if (uuid.equals(stall.getRenter())) {
            throw fail("You already rent this stall. Pay ahead with /market extend <days>.");
        }
        if (stall.isRented()) {
            throw fail("This stall is already rented.");
        }
        if (!player.hasPermission("thebox.market.rent")) {
            throw fail("You haven't unlocked renting a stall yet.");
        }
        for (Stall other : m.getStalls().values()) {
            if (uuid.equals(other.getRenter())) {
                throw fail("You already rent stall <stall> here. Each player can rent one stall per market.", stallPlaceholder(other));
            }
        }
        if (days > market.getMaxPrepayDays()) {
            throw fail("You can pay for at most " + market.getMaxPrepayDays() + " days at a time.");
        }
        Economy economy = requireEconomy(player);
        double cost = stall.getRentPerDay() * days;
        pay(economy, player, cost, "Renting this stall for " + days + " day(s)");
        market.startRental(stall, uuid, days);

        logger.info(player.getName() + " rented stall " + m.getId() + "/" + stall.getId() + " for " + days + " day(s), " + cost + ".");
        player.sendRichMessage("<green>You rented stall <white><stall></white> in </green><market><green> for " + days + " day(s) (<cost>). "
                + "Rent is paid from your balance each day after that; turn it off with /market autorenew off.",
            stallPlaceholder(stall), marketPlaceholder(m), Placeholder.unparsed("cost", economy.format(cost)));
        return Command.SINGLE_SUCCESS;
    }

    private int extend(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Stall stall = ownStall(player);
        int days = IntegerArgumentType.getInteger(ctx, "days");
        long now = System.currentTimeMillis();
        long day = market.getDayMillis();

        // Extending continues from where rent was paid up to, so overdue days are paid for too.
        long newPaidUntil = stall.getPaidUntil() + days * day;
        long maxPaidUntil = now + market.getMaxPrepayDays() * day;
        if (newPaidUntil > maxPaidUntil) {
            long allowed = (maxPaidUntil - stall.getPaidUntil()) / day;
            throw fail(allowed <= 0
                ? "Your rent is already paid as far ahead as allowed (" + market.getMaxPrepayDays() + " days)."
                : "You can pay for at most " + allowed + " more day(s) right now.");
        }
        if (newPaidUntil <= now) {
            long needed = (now - stall.getPaidUntil()) / day + 1;
            throw fail("That doesn't cover what you owe. Pay for at least " + needed + " day(s).");
        }
        Economy economy = requireEconomy(player);
        double cost = stall.getRentPerDay() * days;
        pay(economy, player, cost, "Paying " + days + " day(s) of rent");
        market.setPaidUntil(stall, newPaidUntil);

        logger.info(player.getName() + " paid " + days + " day(s) of rent for stall " + stall.getMarketId() + "/" + stall.getId() + ", " + cost + ".");
        player.sendRichMessage("<green>Paid " + days + " day(s) of rent (<cost>). Rent is paid for the next <time>.",
            Placeholder.unparsed("cost", economy.format(cost)), Placeholder.unparsed("time", Durations.format(newPaidUntil - now)));
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        Stall here = market.getStallAt(player.getLocation());
        if (here != null && !uuid.equals(here.getRenter())) {
            Marketplace m = market.get(here.getMarketId());
            if (here.isRented()) {
                player.sendRichMessage("<gray>Stall <white><stall></white> in </gray><market><gray> is rented by <white><name></white>.",
                    stallPlaceholder(here), marketPlaceholder(m), Placeholder.unparsed("name", nameOf(player, here.getRenter())));
            } else {
                player.sendRichMessage("<green>Stall <white><stall></white> in </green><market><green> is free: <rent> a day. Rent it with /market rent [days].",
                    stallPlaceholder(here), marketPlaceholder(m), Placeholder.unparsed("rent", VaultEconomy.format(player, here.getRentPerDay())));
            }
        }

        List<Stall> mine = market.getStallsRentedBy(uuid);
        if (mine.isEmpty() && here == null) {
            player.sendRichMessage("<gray>You don't rent a stall. Go to the market with /market, stand in a free stall and use /market rent.");
        }
        for (Stall stall : mine) {
            String status = stall.isFrozen()
                ? "<red>rent overdue: cleared in " + Durations.format(stall.getPaidUntil() + market.getGraceDays() * market.getDayMillis() - now) + " unless paid</red>"
                : "paid for " + Durations.format(stall.getPaidUntil() - now) + (stall.isAutoRenew() ? " (renews automatically)" : " (auto-renew off)");
            List<String> helpers = stall.getHelpers().stream().map(helper -> nameOf(player, helper)).toList();
            player.sendRichMessage("<gold>Your stall <white><stall></white> in </gold><market><gold>:</gold> <gray><rent> a day, " + status
                    + (helpers.isEmpty() ? "" : ". Helpers: <helpers>"),
                stallPlaceholder(stall), marketPlaceholder(market.get(stall.getMarketId())),
                Placeholder.unparsed("rent", VaultEconomy.format(player, stall.getRentPerDay())),
                Placeholder.unparsed("helpers", String.join(", ", helpers)));
        }
        int waiting = market.getImpound().count(uuid);
        if (waiting > 0) {
            player.sendRichMessage("<gold>" + waiting + " stack(s) are waiting in your impound: /market claim");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int autoRenew(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Stall stall = ownStall(player);
        market.setAutoRenew(stall, on);
        player.sendRichMessage(on
            ? "<green>Rent for stall <white><stall></white> is now paid from your balance each day."
            : "<gold>Auto-renew is off for stall <white><stall></white>: pay ahead with /market extend <days>, or it'll be cleared.",
            stallPlaceholder(stall));
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers (trusted builders)

    private int trust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Stall stall = ownStall(player);
        List<Player> targets = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (targets.size() != 1) {
            throw fail("Name one online player.");
        }
        Player target = targets.getFirst();
        if (target.equals(player)) {
            throw fail("It's your stall: you can already build there.");
        }
        if (stall.getHelpers().contains(target.getUniqueId())) {
            throw fail("<name> can already build in your stall.", Placeholder.unparsed("name", target.getName()));
        }
        if (stall.getHelpers().size() >= market.getMaxHelpers()) {
            throw fail("You can trust at most " + market.getMaxHelpers() + " player(s). Untrust someone first.");
        }
        market.addHelper(stall, target.getUniqueId());
        player.sendRichMessage("<green><name> can now build in your stall <white><stall></white>.",
            Placeholder.unparsed("name", target.getName()), stallPlaceholder(stall));
        target.sendRichMessage("<green><name> trusted you to build in their market stall.", Placeholder.unparsed("name", player.getName()));
        return Command.SINGLE_SUCCESS;
    }

    private int untrust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Stall stall = ownStall(player);
        String name = StringArgumentType.getString(ctx, "player");
        for (UUID helper : stall.getHelpers()) {
            if (name.equalsIgnoreCase(player.getServer().getOfflinePlayer(helper).getName())) {
                market.removeHelper(stall, helper);
                player.sendRichMessage("<green><name> can no longer build in your stall.", Placeholder.unparsed("name", name));
                return Command.SINGLE_SUCCESS;
            }
        }
        throw fail("<name> isn't trusted in your stall.", Placeholder.unparsed("name", name));
    }

    // ---------------------------------------------------------------- giving up a stall, the impound

    private int unrentInfo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Stall stall = ownStall(player);
        player.sendRichMessage("<gold>Giving up stall <white><stall></white> frees it right away.</gold> <gray>Everything in it goes to your impound "
            + "(/market claim), and paid days aren't refunded. Run <yellow>/market unrent confirm</yellow> to do it.", stallPlaceholder(stall));
        return Command.SINGLE_SUCCESS;
    }

    private int unrent(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Stall stall = ownStall(player);
        int stacks = market.evict(stall);
        if (stacks < 0) {
            throw fail("That stall's world isn't loaded right now. Try again later.");
        }
        logger.info(player.getName() + " gave up stall " + stall.getMarketId() + "/" + stall.getId() + " (" + stacks + " stacks impounded).");
        player.sendRichMessage("<green>Stall <white><stall></white> is no longer yours." + (stacks > 0 ? " " + stacks + " stack(s) went to your impound: /market claim" : ""),
            stallPlaceholder(stall));
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

    /** The stall the player rents: the only one, or the one they're standing in if they rent several. */
    private Stall ownStall(Player player) throws CommandSyntaxException {
        List<Stall> mine = market.getStallsRentedBy(player.getUniqueId());
        if (mine.isEmpty()) {
            throw fail("You don't rent a stall.");
        }
        if (mine.size() == 1) {
            return mine.getFirst();
        }
        Stall here = market.getStallAt(player.getLocation());
        if (here != null && player.getUniqueId().equals(here.getRenter())) {
            return here;
        }
        throw fail("You rent stalls in several markets: stand in the one you mean.");
    }

    private static Economy requireEconomy(Player player) throws CommandSyntaxException {
        Economy economy = VaultEconomy.find(player.getServer());
        if (economy == null) {
            throw fail("Renting is unavailable: no economy plugin is installed.");
        }
        return economy;
    }

    /** Takes money from the player, or explains why it can't. */
    private static void pay(Economy economy, Player player, double cost, String what) throws CommandSyntaxException {
        if (!economy.has(player, cost)) {
            throw fail(what + " costs <cost>. You have <balance>.",
                Placeholder.unparsed("cost", economy.format(cost)),
                Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
        }
        if (!economy.withdrawPlayer(player, cost).transactionSuccess()) {
            throw fail("The economy refused the payment.");
        }
    }

    private static String nameOf(Player viewer, UUID uuid) {
        OfflinePlayer offline = viewer.getServer().getOfflinePlayer(uuid);
        return offline.getName() != null ? offline.getName() : uuid.toString();
    }
}
