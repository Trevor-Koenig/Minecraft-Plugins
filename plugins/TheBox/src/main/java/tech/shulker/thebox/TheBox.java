// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import net.luckperms.api.LuckPerms;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import tech.shulker.thebox.cells.CellAdminCommand;
import tech.shulker.thebox.cells.CellCommand;
import tech.shulker.thebox.cells.CellManager;
import tech.shulker.thebox.effects.EffectPerks;
import tech.shulker.thebox.effects.EffectsCommand;
import tech.shulker.thebox.market.Impound;
import tech.shulker.thebox.market.MarketAdminCommand;
import tech.shulker.thebox.market.MarketCommand;
import tech.shulker.thebox.market.MarketManager;
import tech.shulker.thebox.market.QuickShopHook;
import tech.shulker.thebox.menu.MenuListener;
import tech.shulker.thebox.mines.MineAdminCommand;
import tech.shulker.thebox.mines.MineCommand;
import tech.shulker.thebox.mines.MineManager;
import tech.shulker.thebox.placeholders.TheBoxExpansion;
import tech.shulker.thebox.ranks.ChatTags;
import tech.shulker.thebox.ranks.PrestigeCommand;
import tech.shulker.thebox.ranks.RankAdminCommand;
import tech.shulker.thebox.ranks.RankLadder;
import tech.shulker.thebox.ranks.RankManager;
import tech.shulker.thebox.ranks.RankupCommand;
import tech.shulker.thebox.ranks.Unlocks;
import tech.shulker.thebox.sell.Earnings;
import tech.shulker.thebox.sell.PriceManager;
import tech.shulker.thebox.sell.SellCommand;
import tech.shulker.thebox.sell.VaultEconomy;
import tech.shulker.thebox.themes.ThemeAdminCommand;
import tech.shulker.thebox.themes.ThemeManager;
import tech.shulker.thebox.managers.PluginManager;
import tech.shulker.thebox.listeners.PlayerListener;

public class TheBox extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        // Themes: each PvP band's name, title and reset messages, from themes.yml (the six default sites the first time)
        ThemeManager themeManager = new ThemeManager(this);
        themeManager.load();

        // Mines: load mines.yml and start the automatic-reset timer
        MineManager mineManager = new MineManager(this, themeManager);
        mineManager.load();
        mineManager.startResetTimer();
        getServer().getPluginManager().registerEvents(mineManager, this); // counts mined blocks for % resets

        // Rank effects (Night Vision, Speed), switched on and off in /effects. Mines give Night Vision too, so each
        // leaves the other's alone: a mine doesn't take away a rank's Night Vision, and switching it off in a mine's
        // area doesn't take away the mine's.
        EffectPerks effectPerks = new EffectPerks(this,
            (player, type) -> type.equals(PotionEffectType.NIGHT_VISION) && mineManager.isInMineArea(player));
        mineManager.setNightVisionPerk(effectPerks::hasNightVision);
        getServer().getPluginManager().registerEvents(effectPerks, this);

        // Selling: load prices.yml, and earnings.yml (lifetime /sell totals, for /sell top)
        PriceManager priceManager = new PriceManager(this);
        priceManager.load();
        Earnings earnings = new Earnings(this);
        earnings.load();
        // PlaceholderAPI is optional (softdepend, so when it's installed it has enabled before TheBox).
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new TheBoxExpansion(this, earnings).register();
            getLogger().info("PlaceholderAPI found: registered %thebox_earned% for leaderboards.");
        }

        // Ranks: load ranks.yml (written with a to z the first time)
        RankManager rankManager = new RankManager(this);
        rankManager.load();

        // Rank ladder (players' ranks live in LuckPerms). LuckPerms is a hard dependency, so it has
        // already enabled and registered its API with Bukkit's services manager.
        LuckPerms luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        RankLadder ladder = new RankLadder(this, luckPerms, themeManager);
        getServer().getPluginManager().registerEvents(ladder, this); // puts new players on the first rank, with its band's title
        effectPerks.watch(luckPerms); // rank-ups and LuckPerms changes update players' effects
        getServer().getPluginManager().registerEvents(new ChatTags(rankManager, ladder), this); // [P2] [C] Name: message
        if (ladder.getTrack() == null) {
            getLogger().warning("LuckPerms has no '" + RankLadder.TRACK + "' track yet: /rankup won't work until you create it (/lp createtrack "
                + RankLadder.TRACK + ").");
        }
        if (ladder.getPrestigeTrack() == null) {
            getLogger().warning("LuckPerms has no '" + RankLadder.PRESTIGE_TRACK + "' track yet: /prestige won't work until you create it (/lp createtrack "
                + RankLadder.PRESTIGE_TRACK + ").");
        }

        // Market: stalls, rent and the impound. Chest menus (marketplace picker, impound) need MenuListener.
        Impound impound = new Impound(this);
        impound.load();
        MarketManager marketManager = new MarketManager(this, impound, priceManager);
        marketManager.load();
        marketManager.startTimer();
        getServer().getPluginManager().registerEvents(marketManager, this); // reminders when players join
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        // QuickShop-Hikari is optional (softdepend), and when it's installed it has enabled before TheBox.
        if (getServer().getPluginManager().isPluginEnabled("QuickShop-Hikari")) {
            QuickShopHook.register(this, marketManager);
            getLogger().info("QuickShop-Hikari found: stall shops are removed with their stall and closed while rent is overdue.");
        }

        // Cells: cells.yml, carving rooms, each cell's regions, upgrades and tier moves. They share the market's
        // impound and shop hook (looked up when needed, so it's QuickShop's when QuickShop is installed).
        CellManager cellManager = new CellManager(this, impound, marketManager::getShopHook);
        cellManager.load();
        cellManager.startTimer();
        getServer().getPluginManager().registerEvents(cellManager, this); // hands out cells when players join

        // What each rank unlocks, read from its LuckPerms group, for rank-up messages and /rankup list.
        Unlocks unlocks = new Unlocks(luckPerms, mineManager, marketManager, cellManager);

        // Economy check. runTask runs on the first server tick, after every plugin has enabled,
        // so the economy plugin has had its chance to register with Vault by then.
        getServer().getScheduler().runTask(this, () -> {
            Economy economy = VaultEconomy.find(getServer());
            if (economy == null) {
                getLogger().warning("No economy plugin found through Vault. /sell won't work until one is installed (e.g. EssentialsX).");
            } else {
                getLogger().info("Using economy: " + economy.getName());
            }
        });

        // Register commands
        SellCommand sellCommand = new SellCommand(priceManager, earnings, getComponentLogger());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            // Player commands stay short and top-level, like on other prison servers.
            event.registrar().register(new MineCommand(mineManager).createNode(), "Go to one of the mines you've unlocked");
            event.registrar().register(sellCommand.createNode(), "Sell items for money");
            event.registrar().register(new RankupCommand(rankManager, ladder, unlocks, themeManager, getLogger()).createNode(), "Buy the next rank, or see them all with /rankup list");
            event.registrar().register(new EffectsCommand(effectPerks).createNode(), "Switch the effects your rank gives on or off");
            event.registrar().register(new PrestigeCommand(rankManager, ladder, themeManager, getLogger()).createNode(), "Start the ranks again with a prestige level");
            event.registrar().register(new MarketCommand(marketManager, getLogger()).createNode(), "Go to the market and rent a stall");
            event.registrar().register(new CellCommand(cellManager, marketManager, getLogger()).createNode(), "Go to your cell, upgrade it, or visit someone's");

            // Admin commands all live under /thebox. Brigadier trees compose: each feature's built
            // node is attached as a child, and the one .requires here guards (and hides) them all.
            event.registrar().register(Commands.literal("thebox")
                .requires(source -> source.getSender().hasPermission("thebox.admin"))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendRichMessage(
                        "<gold>TheBox admin:</gold> <yellow>/thebox mine</yellow>, <yellow>/thebox price</yellow>, <yellow>/thebox rank</yellow>, <yellow>/thebox theme</yellow>, <yellow>/thebox market</yellow>, <yellow>/thebox cell</yellow>");
                    return Command.SINGLE_SUCCESS;
                })
                .then(new MineAdminCommand(mineManager, themeManager).createNode())
                .then(sellCommand.createPriceNode())
                .then(new RankAdminCommand(rankManager).createNode())
                .then(new ThemeAdminCommand(themeManager).createNode())
                .then(new MarketAdminCommand(marketManager).createNode())
                .then(new CellAdminCommand(cellManager).createNode())
                // "tb" is an alias: a copy of the whole tree under a second label. Paper skips an alias
                // that another plugin already uses, but /thebox and /thebox:thebox always work.
                .build(), "TheBox admin commands", List.of("tb"));
        });

        getServer().getOnlinePlayers().forEach(effectPerks::refresh); // only matters after a plugin reload
        getLogger().info(getPluginMeta().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getPluginMeta().getName() + " has been disabled!");
    }
    
}
