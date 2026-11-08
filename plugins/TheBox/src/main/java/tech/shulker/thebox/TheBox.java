package tech.shulker.thebox;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.mines.MineCommand;
import tech.shulker.thebox.mines.MineManager;
import tech.shulker.thebox.mines.MinesCommand;
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
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(new MineCommand(mineManager).createNode(), "Manage prison mines");
            event.registrar().register(new MinesCommand(mineManager).createNode(), "Teleport to a mine you've unlocked");
            event.registrar().register(new SellCommand(priceManager, getComponentLogger()).createNode(), "Sell items for money");
        });

        getLogger().info(getPluginMeta().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getPluginMeta().getName() + " has been disabled!");
    }
    
}
