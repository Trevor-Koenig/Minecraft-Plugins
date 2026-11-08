// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.event.economy.ShopPurchaseEvent;
import com.ghostchu.quickshop.api.shop.Shop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The QuickShop-Hikari side of the market. Only created when QuickShop-Hikari is installed.
 *
 * Players can only create shops where they could break blocks (QuickShop checks with a simulated
 * BlockBreakEvent, and WorldGuard answers), so shops can only go in a stall the player rents. This class
 * handles the rest: removing shops when a stall is cleared, and closing shops in unpaid stalls.
 */
public class QuickShopHook implements ShopHook, Listener {

    private final MarketManager market;

    private QuickShopHook(MarketManager market) {
        this.market = market;
    }

    /**
     * Connects the market to QuickShop-Hikari. Call it only when QuickShop-Hikari is enabled. It's a static
     * method on purpose: TheBox's main class then never mentions the QuickShopHook type, so Java never tries
     * to load QuickShop's classes on a server that doesn't have QuickShop.
     */
    public static void register(JavaPlugin plugin, MarketManager market) {
        QuickShopHook hook = new QuickShopHook(market);
        market.setShopHook(hook);
        plugin.getServer().getPluginManager().registerEvents(hook, plugin);
    }

    /**
     * Clearing a stall sets blocks directly, which fires no block events, so QuickShop wouldn't notice its
     * shop chests disappearing and would keep "ghost" shops. Delete them through its API first.
     */
    @Override
    public void removeShopAt(Location location) {
        Shop<?, ?> shop = QuickShopAPI.getInstance().getShopManager().getShop(location);
        if (shop != null) {
            QuickShopAPI.getInstance().getShopManager().deleteShop(shop);
        }
    }

    /** Shops in a stall whose rent is overdue are closed until it's paid. */
    @EventHandler(ignoreCancelled = true)
    public void onPurchase(ShopPurchaseEvent event) {
        Stall stall = market.getStallAt(event.getShop().bukkitLocation());
        if (stall != null && stall.isFrozen()) {
            event.setCancelled(true, Component.text("This shop is closed: the stall's rent is overdue.", NamedTextColor.RED));
        }
    }
}
