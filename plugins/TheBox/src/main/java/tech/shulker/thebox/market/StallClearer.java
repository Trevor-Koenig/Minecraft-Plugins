// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockType;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemType;
import org.bukkit.util.BoundingBox;

/**
 * Empties a stall and returns everything that was in it as items, for the impound.
 *
 * Order matters, so nothing is lost and nothing is duplicated:
 * 1. Entities (item frames, armor stands, paintings, dropped items) become items and are removed first,
 *    so none of them pop off as loose drops when the blocks they hang on disappear.
 * 2. Every block is collected before any block is removed. Shops are deleted, container contents are taken
 *    out (and the container emptied, so a shulker box can't come back with its contents a second time),
 *    then the block itself becomes the item it would drop when mined with Silk Touch.
 * 3. Only then are the blocks set to air, without physics, so nothing attached drops as a loose item.
 */
public class StallClearer {

    /** Silk Touch makes blocks drop themselves (glass, ores, ...). Netherite mines everything a player can. */
    private static final ItemStack SILK_TOUCH_PICKAXE = createSilkTouchPickaxe();

    private static ItemStack createSilkTouchPickaxe() {
        ItemStack pickaxe = ItemType.NETHERITE_PICKAXE.createItemStack();
        pickaxe.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
        return pickaxe;
    }

    /** Clears the stall. Returns its contents, or an empty list if the stall's world isn't loaded. */
    public static List<ItemStack> clear(Stall stall, ShopHook shops) {
        World world = stall.getWorld();
        if (world == null) {
            return Collections.emptyList();
        }
        List<ItemStack> items = new ArrayList<>();

        // 1. Entities inside the stall. Other entities (mobs, minecarts, ...) are left alone.
        BoundingBox box = new BoundingBox(stall.getMinX(), stall.getMinY(), stall.getMinZ(),
            stall.getMaxX() + 1, stall.getMaxY() + 1, stall.getMaxZ() + 1);
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!stall.contains(entity.getLocation())) {
                continue;
            }
            if (entity instanceof Item dropped) {
                items.add(dropped.getItemStack());
            } else if (entity instanceof ItemFrame frame) {
                items.add(frame.getItem());
                // Not getPickItemStack(): like middle-clicking in game, that gives the framed item, not the frame.
                items.add((frame instanceof GlowItemFrame ? ItemType.GLOW_ITEM_FRAME : ItemType.ITEM_FRAME).createItemStack());
            } else if (entity instanceof ArmorStand stand) {
                EntityEquipment equipment = stand.getEquipment();
                Collections.addAll(items, equipment.getArmorContents());
                items.add(equipment.getItemInMainHand());
                items.add(equipment.getItemInOffHand());
                items.add(stand.getPickItemStack());
            } else if (entity instanceof Painting painting) {
                items.add(painting.getPickItemStack()); // keeps the painting's variant
            } else {
                continue;
            }
            entity.remove();
        }

        // 2. Collect every block before removing any.
        List<Block> toClear = new ArrayList<>();
        for (int x = stall.getMinX(); x <= stall.getMaxX(); x++) {
            for (int y = stall.getMinY(); y <= stall.getMaxY(); y++) {
                for (int z = stall.getMinZ(); z <= stall.getMaxZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isEmpty()) {
                        continue;
                    }
                    shops.removeShopAt(block.getLocation());
                    // getState(false): the live block state, not a copy, so emptying the inventory empties the block.
                    BlockState state = block.getState(false);
                    // A chest's getInventory() is the whole double chest; getBlockInventory() is just this half,
                    // so a double chest isn't collected twice.
                    Inventory inventory = state instanceof Chest chest ? chest.getBlockInventory()
                        : state instanceof BlockInventoryHolder holder ? holder.getInventory()
                        : null;
                    if (inventory != null) {
                        Collections.addAll(items, inventory.getContents());
                        inventory.clear();
                    }
                    // Drops follow Minecraft's loot tables, so a door or bed drops once (from one of its halves).
                    items.addAll(block.getDrops(SILK_TOUCH_PICKAXE));
                    toClear.add(block);
                }
            }
        }

        // 3. Remove the blocks. applyPhysics=false: neighbors aren't updated, so torches, signs and the like
        //    don't break off and drop a second copy.
        BlockData air = BlockType.AIR.createBlockData();
        for (Block block : toClear) {
            block.setBlockData(air, false);
        }

        items.removeIf(item -> item == null || item.isEmpty());
        return items;
    }
}
