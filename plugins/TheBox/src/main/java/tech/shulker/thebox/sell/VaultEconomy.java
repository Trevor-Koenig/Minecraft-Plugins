// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.sell;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Finds the server's economy through Vault. */
public class VaultEconomy {

    /**
     * Returns the economy an economy plugin (e.g. EssentialsX) registered with Bukkit's services manager,
     * or null if none is installed. Look it up when needed rather than once in onEnable: TheBox depends on
     * Vault, not on the economy plugin, so the economy may register after TheBox has enabled.
     */
    public static Economy find(Server server) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    /** Formats money in the economy's currency format ("$1,000.00") if there is one, as a plain number otherwise. */
    public static String format(CommandSender sender, double amount) {
        Economy economy = find(sender.getServer());
        return economy != null ? economy.format(amount) : String.format("%.2f", amount);
    }
}
