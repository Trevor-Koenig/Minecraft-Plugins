// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import org.bukkit.Location;

/**
 * What the market needs from a shop plugin. TheBox works without one: then NONE is used.
 * With QuickShop-Hikari installed, QuickShopHook is used instead. Keeping QuickShop's classes behind this
 * interface means they're only loaded when QuickShop is actually there (it's a softdepend).
 */
public interface ShopHook {

    /** Deletes the shop at this block, if there is one. Called before a stall's blocks are cleared. */
    void removeShopAt(Location location);

    ShopHook NONE = location -> { };
}
