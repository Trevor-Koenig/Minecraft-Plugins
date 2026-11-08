package tech.shulker.thebox;

import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.managers.PluginManager;
import tech.shulker.thebox.listeners.PlayerListener;

public class TheBox extends JavaPlugin {
    
    @Override
    public void onEnable() {
        
        // Initialize managers
        PluginManager.getInstance().initialize();
        
        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        
        getLogger().info(getDescription().getName() + " has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info(getDescription().getName() + " has been disabled!");
    }
    
}
