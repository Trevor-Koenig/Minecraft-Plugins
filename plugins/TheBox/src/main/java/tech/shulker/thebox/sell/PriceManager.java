// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.sell;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemType;
import org.bukkit.plugin.java.JavaPlugin;

/** Global sell prices (item type -> price per item), stored in prices.yml. */
public class PriceManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<ItemType, Double> prices = new HashMap<>();

    public PriceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "prices.yml");
    }

    /** The price of one item of this type, or null if it can't be sold. */
    public Double getPrice(ItemType type) {
        return prices.get(type);
    }

    public void setPrice(ItemType type, double price) {
        prices.put(type, price);
        save();
    }

    /** Returns false if the item had no price. */
    public boolean removePrice(ItemType type) {
        boolean removed = prices.remove(type) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** All prices, sorted by item id. */
    public List<Map.Entry<ItemType, Double>> getAllSorted() {
        return prices.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().key().asString()))
            .toList();
    }

    public void load() {
        prices.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("prices");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            NamespacedKey itemKey = NamespacedKey.fromString(key);
            ItemType type = itemKey == null ? null : Registry.ITEM.get(itemKey);
            double price = section.getDouble(key);
            if (type == null || price <= 0) {
                plugin.getLogger().warning("prices.yml: ignoring unknown item or bad price '" + key + "'.");
                continue;
            }
            prices.put(type, price);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox sell prices: item id -> price per item. Edited by /thebox price commands.",
            "You can also edit this file by hand: run /thebox price reload afterwards,",
            "otherwise the next /thebox price change will overwrite your edits."));
        for (Map.Entry<ItemType, Double> entry : getAllSorted()) {
            yaml.set("prices." + entry.getKey().key().asString(), entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save prices.yml: " + e.getMessage());
        }
    }
}
