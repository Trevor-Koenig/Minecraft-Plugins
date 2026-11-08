package tech.shulker.thebox;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.mines.MineCommand;
import tech.shulker.thebox.managers.PluginManager;
import tech.shulker.thebox.listeners.PlayerListener;

public class TheBox extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);

        // Register commands
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(MineCommand.createNode(), "Manage prison mines")
        );

        getLogger().info(getPluginMeta().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getPluginMeta().getName() + " has been disabled!");
    }
    
}
