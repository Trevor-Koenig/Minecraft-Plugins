// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.sell;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.inventory.PlayerInventory;

/**
 * The player command /sell hand|all|worth, plus the admin-only /thebox price set|remove|list|reload.
 */
public class SellCommand {

    private static final SimpleCommandExceptionType NO_ECONOMY = new SimpleCommandExceptionType(
            new LiteralMessage("Selling is unavailable: no economy plugin is installed."));
    private static final SimpleCommandExceptionType EMPTY_HAND = new SimpleCommandExceptionType(
            new LiteralMessage("You're not holding anything."));
    // These two show the item's name: its custom name if a player renamed it, so
    // funny names make funny errors.
    private static final DynamicCommandExceptionType NOT_SELLABLE = new DynamicCommandExceptionType(
            name -> itemMessage("<item> can't be sold.", name));
    private static final DynamicCommandExceptionType CUSTOM_ITEM = new DynamicCommandExceptionType(
            name -> itemMessage("<item> cannot be sold because it is renamed, enchanted or otherwise customized.",
                    name));
    private static final SimpleCommandExceptionType NOTHING_TO_SELL = new SimpleCommandExceptionType(
            new LiteralMessage("You have nothing sellable in your inventory."));
    private static final SimpleCommandExceptionType NO_PRICE = new SimpleCommandExceptionType(
            new LiteralMessage("That item doesn't have a price."));
    private static final DynamicCommandExceptionType DEPOSIT_FAILED = new DynamicCommandExceptionType(
            error -> new LiteralMessage("The economy refused the payment: " + error));

    private final PriceManager prices;
    private final ComponentLogger logger;

    public SellCommand(PriceManager prices, ComponentLogger logger) {
        this.prices = prices;
        this.logger = logger;
    }

    public LiteralCommandNode<CommandSourceStack> createNode() {
        return Commands.literal("sell")
                .executes(this::help)
                .then(Commands.literal("hand").executes(ctx -> logErrors(ctx, "hand", this::sellHand)))
                .then(Commands.literal("all").executes(ctx -> logErrors(ctx, "all", this::sellAll)))
                .then(Commands.literal("worth").executes(this::worth))
                .build();
    }

    /** The admin price commands. Mounted under /thebox, which checks thebox.admin. */
    public LiteralCommandNode<CommandSourceStack> createPriceNode() {
        return Commands.literal("price")
                .then(Commands.literal("set")
                        .then(Commands.argument("item", ArgumentTypes.resource(RegistryKey.ITEM))
                                .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                        .executes(this::setPrice))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("item", ArgumentTypes.resource(RegistryKey.ITEM))
                                .executes(this::removePrice)))
                .then(Commands.literal("list").executes(this::listPrices))
                .then(Commands.literal("reload").executes(this::reloadPrices))
                .build();
    }

    // ---------------------------------------------------------------- selling

    private int help(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().getSender().sendRichMessage(
                """
                        <gold>Selling:</gold>
                        <yellow>/sell hand</yellow> <gray>- sell the stack you're holding</gray>
                        <yellow>/sell all</yellow> <gray>- sell everything sellable in your inventory (customized items are skipped)</gray>
                        <yellow>/sell worth</yellow> <gray>- check what the item you're holding sells for</gray>""");
        return Command.SINGLE_SUCCESS;
    }

    private int sellHand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Economy economy = requireEconomy(player);
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = inventory.getItemInMainHand();

        if (stack.isEmpty()) {
            throw EMPTY_HAND.create();
        }
        ItemType type = typeOf(stack);
        Double price = prices.getPrice(type);
        if (price == null) {
            throw NOT_SELLABLE.create(stack.effectiveName());
        }
        if (!isPlain(stack, type)) {
            throw CUSTOM_ITEM.create(stack.effectiveName());
        }

        int amount = stack.getAmount();
        double total = price * amount;
        deposit(economy, player, total); // pay first: if the economy refuses, the player keeps the items
        inventory.setItemInMainHand(null);

        reply(player, "hand", "<green>Sold <count> <item> for <total>.</green> <gray>Balance: <balance>",
                Placeholder.unparsed("count", String.valueOf(amount)),
                Placeholder.unparsed("item", type.key().value()),
                Placeholder.unparsed("total", economy.format(total)),
                Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
        return Command.SINGLE_SUCCESS;
    }

    private int sellAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        Economy economy = requireEconomy(player);
        PlayerInventory inventory = player.getInventory();

        // First pass: work out what to sell, without touching the inventory yet.
        // Storage slots are the hotbar and main inventory; armor and the off hand are
        // left alone.
        List<Integer> slotsToClear = new ArrayList<>();
        double total = 0;
        int itemCount = 0;
        int skippedCustom = 0;
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemType type = typeOf(stack);
            Double price = prices.getPrice(type);
            if (price == null) {
                continue;
            }
            if (!isPlain(stack, type)) {
                skippedCustom++;
                continue;
            }
            total += price * stack.getAmount();
            itemCount += stack.getAmount();
            slotsToClear.add(slot);
        }

        if (slotsToClear.isEmpty()) {
            throw NOTHING_TO_SELL.create();
        }

        // Second pass: pay, then remove the sold stacks.
        deposit(economy, player, total);
        for (int slot : slotsToClear) {
            inventory.setItem(slot, null);
        }

        reply(player, "all", "<green>Sold <count> items for <total>.</green> <gray>Balance: <balance>",
                Placeholder.unparsed("count", String.valueOf(itemCount)),
                Placeholder.unparsed("total", economy.format(total)),
                Placeholder.unparsed("balance", economy.format(economy.getBalance(player))));
        if (skippedCustom > 0) {
            reply(player, "all", "<gray>Kept " + skippedCustom
                    + " customized stack(s) (renamed, enchanted, etc.): those can't be sold.");
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- prices
    // (admin)

    /** Tells a player what the item in their hand sells for, without selling it. */
    private int worth(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player player = ctx.getSource().getPlayerOrThrow();
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack.isEmpty()) {
            throw EMPTY_HAND.create();
        }
        ItemType type = typeOf(stack);
        Double price = prices.getPrice(type);
        if (price == null) {
            throw NOT_SELLABLE.create(stack.effectiveName());
        }
        String customizedNote = isPlain(stack, type) ? "" : " <gray>(This one is customized, though, so it can't be sold.)";
        player.sendRichMessage("<item> <green>sells for <each> each, <total> for this stack.</green>" + customizedNote,
                Placeholder.component("item", stack.effectiveName()),
                Placeholder.unparsed("each", VaultEconomy.format(player, price)),
                Placeholder.unparsed("total", VaultEconomy.format(player, price * stack.getAmount())));
        return Command.SINGLE_SUCCESS;
    }

    private int setPrice(CommandContext<CommandSourceStack> ctx) {
        ItemType type = ctx.getArgument("item", ItemType.class);
        double price = DoubleArgumentType.getDouble(ctx, "price");
        prices.setPrice(type, price);
        ctx.getSource().getSender().sendRichMessage("<green>Price of <item> set to <price> each.",
                Placeholder.unparsed("item", type.key().value()),
                Placeholder.unparsed("price", VaultEconomy.format(ctx.getSource().getSender(), price)));
        return Command.SINGLE_SUCCESS;
    }

    private int removePrice(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ItemType type = ctx.getArgument("item", ItemType.class);
        if (!prices.removePrice(type)) {
            throw NO_PRICE.create();
        }
        ctx.getSource().getSender().sendRichMessage("<green><item> can no longer be sold.",
                Placeholder.unparsed("item", type.key().value()));
        return Command.SINGLE_SUCCESS;
    }

    private int listPrices(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<Map.Entry<ItemType, Double>> all = prices.getAllSorted();
        if (all.isEmpty()) {
            sender.sendRichMessage("<gray>No prices set yet. Use /thebox price set \\<item> \\<price>.");
            return Command.SINGLE_SUCCESS;
        }
        sender.sendRichMessage("<gold>Sell prices:");
        for (Map.Entry<ItemType, Double> entry : all) {
            sender.sendRichMessage("<yellow><item></yellow> <gray>- <price>",
                    Placeholder.unparsed("item", entry.getKey().key().value()),
                    Placeholder.unparsed("price", VaultEconomy.format(sender, entry.getValue())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reloadPrices(CommandContext<CommandSourceStack> ctx) {
        prices.load();
        ctx.getSource().getSender()
                .sendRichMessage("<green>Reloaded prices.yml: " + prices.getAllSorted().size() + " price(s).");
        return Command.SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------------- helpers

    /** Sends a message to the player and writes the same message to the server log. */
    private void reply(Player player, String subcommand, String template, TagResolver... placeholders) {
        Component message = MiniMessage.miniMessage().deserialize(template, placeholders);
        player.sendMessage(message);
        log(player, subcommand, message);
    }

    /**
     * Runs an executor, logging any error it throws before passing the error on to Brigadier as usual.
     * Wrapping the executor means every error is logged, including ones added later.
     */
    private int logErrors(CommandContext<CommandSourceStack> ctx, String subcommand,
            Command<CommandSourceStack> executor) throws CommandSyntaxException {
        try {
            return executor.run(ctx);
        } catch (CommandSyntaxException e) {
            log(ctx.getSource().getSender(), subcommand, MessageComponentSerializer.message().deserialize(e.getRawMessage()));
            throw e;
        }
    }

    /**
     * Logs through Paper's ComponentLogger, which renders components in the console: translated item names
     * show as "Dirt", not as the raw key "block.minecraft.dirt".
     */
    private void log(CommandSender sender, String subcommand, Component message) {
        logger.info(Component.text("/sell " + subcommand + " -> " + sender.getName() + ": ").append(message));
    }

    /**
     * Builds an error message with an item name in it. The name goes in as a
     * component placeholder, never as
     * MiniMessage text: names are player-chosen, and parsing them would let players
     * inject formatting or click events.
     * MessageComponentSerializer turns the result into a Brigadier message for the
     * red error text.
     */
    private static Message itemMessage(String template, Object name) {
        Component message = MiniMessage.miniMessage().deserialize(template,
                Placeholder.component("item", (Component) name));
        return MessageComponentSerializer.message().serialize(message);
    }

    private static Economy requireEconomy(CommandSender sender) throws CommandSyntaxException {
        Economy economy = VaultEconomy.find(sender.getServer());
        if (economy == null) {
            throw NO_ECONOMY.create();
        }
        return economy;
    }

    private static void deposit(Economy economy, Player player, double amount) throws CommandSyntaxException {
        EconomyResponse response = economy.depositPlayer(player, amount);
        if (!response.transactionSuccess()) {
            throw DEPOSIT_FAILED.create(response.errorMessage);
        }
    }

    /**
     * The stack's item type. In Paper 26.3, ItemStack only exposes its type as the
     * deprecated Material,
     * so we convert straight to the registry-based ItemType here, in this one
     * place.
     */
    @SuppressWarnings("deprecation")
    private static ItemType typeOf(ItemStack stack) {
        return stack.getType().asItemType();
    }

    /**
     * True if the stack is a plain item: identical to a freshly created one apart
     * from the amount.
     */
    private static boolean isPlain(ItemStack stack, ItemType type) {
        return stack.isSimilar(type.createItemStack());
    }
}
