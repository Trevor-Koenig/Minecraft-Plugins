package tech.shulker.thebox;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.mines.MineCommand;
import tech.shulker.thebox.mines.MineManager;
import tech.shulker.thebox.mines.MinesCommand;
import tech.shulker.thebox.ranks.RankCommand;
import tech.shulker.thebox.ranks.RankManager;
import tech.shulker.thebox.sell.PriceManager;
import tech.shulker.thebox.sell.SellCommand;
import tech.shulker.thebox.sell.VaultEconomy;
import tech.shulker.thebox.managers.PluginManager;
import tech.shulker.thebox.listeners.PlayerListener;

public class TheBox extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        // Mines: load mines.yml and start the automatic-reset timer
        MineManager mineManager = new MineManager(this);
        mineManager.load();
        mineManager.startResetTimer();
        getServer().getPluginManager().registerEvents(mineManager, this); // counts mined blocks for % resets

        // Selling: load prices.yml
        PriceManager priceManager = new PriceManager(this);
        priceManager.load();

        // Ranks: load ranks.yml (written with a to z the first time)
        RankManager rankManager = new RankManager(this);
        rankManager.load();

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
        SellCommand sellCommand = new SellCommand(priceManager, getComponentLogger());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            // Player commands stay short and top-level, like on other prison servers.
            event.registrar().register(new MinesCommand(mineManager).createNode(), "Teleport to a mine you've unlocked");
            event.registrar().register(sellCommand.createNode(), "Sell items for money");

            // Admin commands all live under /thebox. Brigadier trees compose: each feature's built
            // node is attached as a child, and the one .requires here guards (and hides) them all.
            event.registrar().register(Commands.literal("thebox")
                .requires(source -> source.getSender().hasPermission("thebox.admin"))
                .executes(ctx -> {
                    ctx.getSource().getSender().sendRichMessage(
                        "<gold>TheBox admin:</gold> <yellow>/thebox mine</yellow>, <yellow>/thebox price</yellow>, <yellow>/thebox rank</yellow>");
                    return Command.SINGLE_SUCCESS;
                })
                .then(new MineCommand(mineManager).createNode())
                .then(sellCommand.createPriceNode())
                .then(new RankCommand(rankManager).createNode())
                // "tb" is an alias: a copy of the whole tree under a second label. Paper skips an alias
                // that another plugin already uses, but /thebox and /thebox:thebox always work.
                .build(), "TheBox admin commands", List.of("tb"));
        });

        getLogger().info(getPluginMeta().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getPluginMeta().getName() + " has been disabled!");
    }
    
}
