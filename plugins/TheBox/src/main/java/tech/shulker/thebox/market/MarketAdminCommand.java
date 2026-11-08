// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import static tech.shulker.thebox.market.MarketManager.marketPlaceholder;
import static tech.shulker.thebox.market.MarketManager.stallPlaceholder;
import static tech.shulker.thebox.utils.Errors.fail;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import tech.shulker.thebox.sell.VaultEconomy;
import tech.shulker.thebox.utils.Durations;
import tech.shulker.thebox.utils.SavedLocation;
import tech.shulker.thebox.utils.Selections;

/** The admin command: /thebox market ... and /thebox market stall ... Mounted under /thebox. */
public class MarketAdminCommand {

    /** Stalls are cleared block by block, so keep them a sensible size. */
    private static final long MAX_STALL_VOLUME = 50_000;

    private final MarketManager market;

    public MarketAdminCommand(MarketManager market) {
        this.market = market;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("market")
            .executes(this::help)
            .then(Commands.literal("create")
                .then(Commands.argument("id", StringArgumentType.word()).executes(this::create)))
            .then(Commands.literal("delete")
                .then(marketArgument().executes(this::delete)))
            .then(Commands.literal("setname")
                .then(marketArgument()
                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(this::setName))))
            .then(Commands.literal("settp")
                .then(marketArgument().executes(this::setTeleport)))
            .then(Commands.literal("list").executes(this::list))
            .then(Commands.literal("reload").executes(this::reload))
            .then(Commands.literal("stall")
                .then(Commands.literal("create")
                    .then(marketArgument()
                        .then(Commands.argument("stall", StringArgumentType.word())
                            .then(Commands.argument("rent", DoubleArgumentType.doubleArg(0)).executes(this::createStall)))))
                .then(Commands.literal("delete")
                    .then(marketArgument().then(stallArgument().executes(this::deleteStall))))
                .then(Commands.literal("setrent")
                    .then(marketArgument().then(stallArgument()
                        .then(Commands.argument("rent", DoubleArgumentType.doubleArg(0)).executes(this::setRent)))))
                .then(Commands.literal("list")
                    .then(marketArgument().executes(this::listStalls)))
                .then(Commands.literal("evict")
                    .then(marketArgument().then(stallArgument().executes(this::evict)))))
            .build();
    }

    // ---------------------------------------------------------------- arguments

    private RequiredArgumentBuilder<CommandSourceStack, String> marketArgument() {
        return Commands.argument("market", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                for (Marketplace m : market.getAll()) {
                    if (m.getId().startsWith(builder.getRemainingLowerCase())) {
                        builder.suggest(m.getId());
                    }
                }
                return builder.buildFuture();
            });
    }

    /** Suggests the stalls of the market typed before it. */
    private RequiredArgumentBuilder<CommandSourceStack, String> stallArgument() {
        return Commands.argument("stall", StringArgumentType.word())
            .suggests((ctx, builder) -> {
                Marketplace m;
                try {
                    m = market.get(StringArgumentType.getString(ctx, "market"));
                } catch (IllegalArgumentException notTypedYet) {
                    m = null;
                }
                if (m != null) {
                    for (String id : m.getStalls().keySet()) {
                        if (id.startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(id);
                        }
                    }
                }
                return builder.buildFuture();
            });
    }

    private Marketplace getMarket(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "market");
        Marketplace m = market.get(id);
        if (m == null) {
            throw fail("Unknown market: <id>", Placeholder.unparsed("id", id));
        }
        return m;
    }

    private Stall getStall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Marketplace m = getMarket(ctx);
        String id = StringArgumentType.getString(ctx, "stall");
        Stall stall = m.getStalls().get(id);
        if (stall == null) {
            throw fail("Unknown stall: <id>", Placeholder.unparsed("id", id));
        }
        return stall;
    }

    // ---------------------------------------------------------------- markets

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage("""
            <gold>TheBox market commands:</gold>
            <yellow>/thebox market create \\<id></yellow> <gray>- create a market (players arrive where you stand)</gray>
            <yellow>/thebox market setname \\<market> \\<name></yellow> <gray>- the name players see (MiniMessage)</gray>
            <yellow>/thebox market settp \\<market></yellow> <gray>- where /market sends players</gray>
            <yellow>/thebox market list</yellow> <gray>- all markets</gray>
            <yellow>/thebox market delete \\<market></yellow> <gray>- delete a market (no rented stalls allowed)</gray>
            <yellow>/thebox market reload</yellow> <gray>- re-read markets.yml</gray>
            <yellow>/thebox market stall create \\<market> \\<stall> \\<rent></yellow> <gray>- make your WorldEdit selection a stall; select only the empty space players may build in</gray>
            <yellow>/thebox market stall setrent \\<market> \\<stall> \\<rent></yellow> <gray>- change the daily rent</gray>
            <yellow>/thebox market stall list \\<market></yellow> <gray>- stalls and renters</gray>
            <yellow>/thebox market stall evict \\<market> \\<stall></yellow> <gray>- clear a stall into its renter's impound</gray>
            <yellow>/thebox market stall delete \\<market> \\<stall></yellow> <gray>- delete a free stall</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String id = StringArgumentType.getString(ctx, "id");
        if (!MarketManager.isValidId(id)) {
            throw fail("Market ids may only use lowercase letters, numbers and _.");
        }
        if (market.get(id) != null) {
            throw fail("A market called <id> already exists.", Placeholder.unparsed("id", id));
        }
        Marketplace m = new Marketplace(id);
        CommandSender sender = ctx.getSource().getSender();
        if (sender instanceof Player player) {
            m.setTeleport(SavedLocation.of(player.getLocation()));
        }
        market.addMarket(m);
        sender.sendRichMessage("<green>Created market <white>" + id + "</white>."
            + (sender instanceof Player ? " Players arrive where you're standing (change it with settp)." : " Set where players arrive with settp.")
            + " Add stalls with /thebox market stall create " + id + " \\<stall> \\<rent>.");
        return Command.SINGLE_SUCCESS;
    }

    private int delete(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Marketplace m = getMarket(ctx);
        for (Stall stall : m.getStalls().values()) {
            if (stall.isRented()) {
                throw fail("Stall <stall> is rented. Evict its renter first: /thebox market stall evict " + m.getId() + " " + stall.getId(),
                    stallPlaceholder(stall));
            }
        }
        market.removeMarket(m);
        ctx.getSource().getSender().sendRichMessage("<green>Deleted market <white>" + m.getId() + "</white> and its stalls' regions. Blocks were left as they are.");
        return Command.SINGLE_SUCCESS;
    }

    private int setName(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Marketplace m = getMarket(ctx);
        m.setDisplayName(StringArgumentType.getString(ctx, "name"));
        market.save();
        ctx.getSource().getSender().sendRichMessage("<green>Market <white>" + m.getId() + "</white> is now shown to players as: </green><market>",
            marketPlaceholder(m));
        return Command.SINGLE_SUCCESS;
    }

    private int setTeleport(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Marketplace m = getMarket(ctx);
        m.setTeleport(SavedLocation.of(player.getLocation()));
        market.save();
        player.sendRichMessage("<green>/market now sends players to where you're standing.");
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (market.getAll().isEmpty()) {
            sender.sendRichMessage("<gray>No markets yet. Create one with /thebox market create \\<id>.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<gold>Markets:");
        for (Marketplace m : market.getAll()) {
            long rented = m.getStalls().values().stream().filter(Stall::isRented).count();
            sender.sendRichMessage("<yellow>" + m.getId() + "</yellow> <gray>-</gray> <market> <gray>- " + m.getStalls().size() + " stall(s), "
                + rented + " rented" + (m.getTeleport() == null ? ", no teleport point set" : ""), marketPlaceholder(m));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        market.load();
        ctx.getSource().getSender().sendRichMessage("<green>Reloaded markets.yml: " + market.getAll().size() + " market(s).");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- stalls

    private int createStall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Marketplace m = getMarket(ctx);
        String id = StringArgumentType.getString(ctx, "stall");
        double rent = DoubleArgumentType.getDouble(ctx, "rent");
        if (!MarketManager.isValidId(id)) {
            throw fail("Stall ids may only use lowercase letters, numbers and _.");
        }
        if (m.getStalls().containsKey(id)) {
            throw fail("Market " + m.getId() + " already has a stall called <id>.", Placeholder.unparsed("id", id));
        }
        Region selection = Selections.get(player);
        if (selection == null) {
            throw fail("Make a WorldEdit selection first (//wand, then click two corners).");
        }
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        World world = player.getWorld();
        long volume = (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
        if (volume > MAX_STALL_VOLUME) {
            throw fail("That's " + volume + " blocks; a stall can be at most " + MAX_STALL_VOLUME + ".");
        }
        if (market.overlapsAnyStall(world.getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z())) {
            throw fail("That selection overlaps another stall.");
        }
        // A stall is the empty space players build in. Everything in it belongs to the renter and is cleared
        // into their impound later, so it must start empty, or the first renter would get those blocks.
        int blocks = 0;
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    if (!world.getBlockAt(x, y, z).isEmpty()) {
                        blocks++;
                    }
                }
            }
        }
        if (blocks > 0) {
            throw fail(blocks + " block(s) are inside the selection. Select only the empty space players may build in: "
                + "above the floor and inside the walls, so the stall's own structure stays protected.");
        }

        Stall stall = new Stall(m.getId(), id, world.getName(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), rent);
        market.addStall(m, stall);
        player.sendRichMessage("<green>Created stall <white>" + id + "</white> in </green><market><green> ("
                + (max.x() - min.x() + 1) + "x" + (max.y() - min.y() + 1) + "x" + (max.z() - min.z() + 1)
                + ", <rent> a day). Its WorldGuard region is <white>" + stall.getRegionId() + "</white>.",
            marketPlaceholder(m), Placeholder.unparsed("rent", VaultEconomy.format(player, rent)));
        return Command.SINGLE_SUCCESS;
    }

    private int deleteStall(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Stall stall = getStall(ctx);
        if (stall.isRented()) {
            throw fail("Stall <stall> is rented. Evict its renter first.", stallPlaceholder(stall));
        }
        market.removeStall(stall);
        ctx.getSource().getSender().sendRichMessage("<green>Deleted stall <white>" + stall.getId() + "</white> and its region.");
        return Command.SINGLE_SUCCESS;
    }

    private int setRent(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Stall stall = getStall(ctx);
        double rent = DoubleArgumentType.getDouble(ctx, "rent");
        stall.setRentPerDay(rent);
        market.save();
        CommandSender sender = ctx.getSource().getSender();
        sender.sendRichMessage("<green>Stall <white>" + stall.getId() + "</white> now rents for <rent> a day."
            + (stall.isRented() ? " The current renter pays the new rent from their next day on." : ""),
            Placeholder.unparsed("rent", VaultEconomy.format(sender, rent)));
        return Command.SINGLE_SUCCESS;
    }

    private int listStalls(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Marketplace m = getMarket(ctx);
        CommandSender sender = ctx.getSource().getSender();
        if (m.getStalls().isEmpty()) {
            sender.sendRichMessage("<gray>No stalls in this market yet.");
            return Command.SINGLE_SUCCESS;
        }
        long now = System.currentTimeMillis();
        sender.sendRichMessage("<gold>Stalls in </gold><market><gold>:", marketPlaceholder(m));
        for (Stall stall : m.getStalls().values()) {
            String status;
            if (!stall.isRented()) {
                status = "<green>free</green>";
            } else {
                UUID renter = stall.getRenter();
                String name = sender.getServer().getOfflinePlayer(renter).getName();
                status = "rented by " + (name != null ? name : renter) + ", "
                    + (stall.isFrozen() ? "<red>overdue</red>" : "paid for " + Durations.format(stall.getPaidUntil() - now));
            }
            sender.sendRichMessage("<yellow>" + stall.getId() + "</yellow> <gray>- <rent>/day, " + status,
                Placeholder.unparsed("rent", VaultEconomy.format(sender, stall.getRentPerDay())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int evict(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Stall stall = getStall(ctx);
        if (!stall.isRented()) {
            throw fail("Stall <stall> isn't rented.", stallPlaceholder(stall));
        }
        UUID renter = stall.getRenter();
        int stacks = market.evict(stall);
        if (stacks < 0) {
            throw fail("That stall's world isn't loaded.");
        }
        CommandSender sender = ctx.getSource().getSender();
        sender.sendRichMessage("<green>Cleared stall <white>" + stall.getId() + "</white>: " + stacks + " stack(s) went to the renter's impound.");
        Player online = sender.getServer().getPlayer(renter);
        if (online != null) {
            online.sendRichMessage("<red>An admin cleared your market stall <white><stall></white>. " + stacks
                + " stack(s) are waiting for you: /market claim", stallPlaceholder(stall));
        }
        return Command.SINGLE_SUCCESS;
    }
}
