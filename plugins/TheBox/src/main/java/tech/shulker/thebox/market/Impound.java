// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.sell.PriceManager;
import tech.shulker.thebox.sell.VaultEconomy;

/**
 * Items taken out of cleared stalls, kept per player until they claim them (/market claim).
 * Unclaimed items are eventually sold at market value (see {@link #expire}).
 *
 * Items are stored with Paper's ItemStack#serializeAsBytes, which keeps everything about an item
 * (enchantments, names, shulker box contents, ...) and is upgraded automatically when Minecraft updates.
 */
public class Impound {

    /** One stack in a player's impound, and when it got there. */
    public record Entry(ItemStack item, long addedMillis) {}

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, List<Entry>> entries = new HashMap<>();

    public Impound(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "impound.yml");
    }

    /** The player's impounded items, oldest first. Changes go through add/remove. */
    public List<Entry> get(UUID owner) {
        return List.copyOf(entries.getOrDefault(owner, List.of()));
    }

    public int count(UUID owner) {
        return entries.getOrDefault(owner, List.of()).size();
    }

    public void add(UUID owner, Collection<ItemStack> items) {
        long now = System.currentTimeMillis();
        List<Entry> list = entries.computeIfAbsent(owner, uuid -> new ArrayList<>());
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                list.add(new Entry(item.clone(), now));
            }
        }
        save();
    }

    /** Removes one entry. Returns false if it was already gone (e.g. two quick clicks in the menu). */
    public boolean remove(UUID owner, Entry entry) {
        List<Entry> list = entries.get(owner);
        // Compare by identity: two identical stacks added at the same moment are equal records, but each
        // menu button belongs to one specific entry.
        boolean removed = list != null && list.removeIf(e -> e == entry);
        if (removed) {
            if (list.isEmpty()) {
                entries.remove(owner);
            }
            save();
        }
        return removed;
    }

    /** Puts back what didn't fit in a player's inventory, keeping its original date. */
    public void putBack(UUID owner, Entry original, Collection<ItemStack> leftovers) {
        List<Entry> list = entries.computeIfAbsent(owner, uuid -> new ArrayList<>());
        for (ItemStack item : leftovers) {
            list.add(new Entry(item, original.addedMillis()));
        }
        save();
    }

    /**
     * Sells items that have waited longer than {@code maxAgeMillis}, at market value (prices.yml; unpriced or
     * customized items are worth nothing), and pays their owners. Does nothing without an economy plugin,
     * so items are never thrown away just because the economy is missing.
     */
    public void expire(long maxAgeMillis, PriceManager prices) {
        Economy economy = VaultEconomy.find(plugin.getServer());
        if (economy == null || entries.isEmpty()) {
            return;
        }
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        boolean changed = false;
        for (Iterator<Map.Entry<UUID, List<Entry>>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<Entry>> owner = it.next();
            double total = 0;
            int sold = 0;
            for (Iterator<Entry> items = owner.getValue().iterator(); items.hasNext(); ) {
                Entry entry = items.next();
                if (entry.addedMillis() < cutoff) {
                    total += prices.getValue(entry.item());
                    sold++;
                    items.remove();
                }
            }
            if (sold == 0) {
                continue;
            }
            changed = true;
            OfflinePlayer player = plugin.getServer().getOfflinePlayer(owner.getKey());
            if (total > 0) {
                economy.depositPlayer(player, total);
            }
            plugin.getLogger().info("Impound: sold " + sold + " unclaimed stack(s) of " + player.getName() + " for " + total + ".");
            Player online = plugin.getServer().getPlayer(owner.getKey());
            if (online != null) {
                online.sendRichMessage("<gray>" + sold + " unclaimed stack(s) in your impound were sold for "
                    + VaultEconomy.format(online, total) + ".");
            }
            if (owner.getValue().isEmpty()) {
                it.remove();
            }
        }
        if (changed) {
            save();
        }
    }

    public void load() {
        entries.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("impound.yml: skipping bad player id '" + key + "'.");
                continue;
            }
            List<Entry> list = new ArrayList<>();
            for (Map<?, ?> map : players.getMapList(key)) {
                try {
                    ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode((String) map.get("item")));
                    long added = ((Number) map.get("added")).longValue();
                    list.add(new Entry(item, added));
                } catch (RuntimeException e) {
                    plugin.getLogger().severe("impound.yml: could not read an item of " + key + ": " + e.getMessage());
                }
            }
            if (!list.isEmpty()) {
                entries.put(owner, list);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox impound: items from cleared market stalls, waiting to be claimed with /market claim.",
            "Items are stored in Paper's binary item format (Base64). Don't edit this file by hand."));
        for (Map.Entry<UUID, List<Entry>> owner : entries.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Entry entry : owner.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("item", Base64.getEncoder().encodeToString(entry.item().serializeAsBytes()));
                map.put("added", entry.addedMillis());
                list.add(map);
            }
            yaml.set("players." + owner.getKey(), list);
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save impound.yml: " + e.getMessage());
        }
    }
}
