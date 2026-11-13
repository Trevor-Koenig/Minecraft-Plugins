// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** The rank ladder's settings (display names, costs), stored in ranks.yml. */
public class RankManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Rank> ranks = new LinkedHashMap<>(); // keeps ladder order, a to z

    /** Each prestige level adds this share of a rank's base cost: 0.5 means +50% per level. */
    private static final double DEFAULT_PRESTIGE_MULTIPLIER = 0.5;
    private double prestigeMultiplier = DEFAULT_PRESTIGE_MULTIPLIER;

    public RankManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "ranks.yml");
    }

    public Collection<Rank> getAll() {
        return ranks.values();
    }

    /** The rank with this id, or null. */
    public Rank get(String id) {
        return ranks.get(id);
    }

    /** The display name of the rank with this id, or the id in capitals if it isn't in ranks.yml. */
    public String getDisplayName(String id) {
        Rank rank = ranks.get(id);
        return rank != null ? rank.getDisplayName() : id.toUpperCase(Locale.ROOT);
    }

    public double getPrestigeMultiplier() { return prestigeMultiplier; }
    public void setPrestigeMultiplier(double multiplier) { this.prestigeMultiplier = multiplier; }

    /** What ranking up to this rank costs a player at the given prestige level. */
    public double getCost(Rank rank, int prestige) {
        return rank.getCost() * (1 + prestigeMultiplier * prestige);
    }

    /** True for the first rank, which players start at, so its cost is never charged. */
    public boolean isFirst(Rank rank) {
        return !ranks.isEmpty() && ranks.values().iterator().next() == rank;
    }

    /** (Re)loads ranks.yml. The first time, writes the default ladder (a to z) with placeholder costs. */
    public void load() {
        ranks.clear();
        prestigeMultiplier = DEFAULT_PRESTIGE_MULTIPLIER;
        if (!file.exists()) {
            createDefaults();
            save();
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        prestigeMultiplier = Math.max(0, yaml.getDouble("prestige-multiplier", DEFAULT_PRESTIGE_MULTIPLIER));
        ConfigurationSection section = yaml.getConfigurationSection("ranks");
        if (section == null) {
            plugin.getLogger().warning("ranks.yml has no 'ranks' section; there are no ranks.");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(id);
            if (r == null || !id.matches("[a-z]")) {
                plugin.getLogger().warning("Skipping rank '" + id + "' in ranks.yml: ranks are single letters, a to z.");
                continue;
            }
            Rank rank = new Rank(id);
            rank.setDisplayName(r.getString("display-name"));
            rank.setCost(Math.max(0, r.getDouble("cost")));
            ranks.put(id, rank);
        }
    }

    /** Writes all ranks to ranks.yml. Called after every change. */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox ranks, in ladder order. Each rank's id is also its LuckPerms group name.",
            "cost: what it costs to rank up to that rank. The first rank is where players start, so its cost is unused.",
            "prestige-multiplier: each prestige level adds this share of the cost (0.5 = +50% per level).",
            "Edited by /thebox rank commands. You can also edit this file by hand: run /thebox rank reload afterwards,",
            "otherwise the next /thebox rank change will overwrite your edits."));
        yaml.set("prestige-multiplier", prestigeMultiplier);
        for (Rank rank : ranks.values()) {
            String path = "ranks." + rank.getId() + ".";
            if (rank.hasDisplayName()) {
                yaml.set(path + "display-name", rank.getDisplayName());
            }
            yaml.set(path + "cost", rank.getCost());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save ranks.yml: " + e.getMessage());
        }
    }

    /**
     * Default costs, a to z (a is where players start, so its cost is unused). They follow the design's pacing formula,
     * rounded: hours for the rank (B 15 min, C 30 min, D 1 h, then +18 min a rank) × an hour's income in the mine before
     * it. Mines A to F, with vanilla pickaxes, pay from $14,400 an hour, each 1.15× the one before. From G, players have
     * god-tier pickaxes that mine about 4× faster, and each mine pays at least 1.2× the one before. README sections 6
     * and 9 list them.
     */
    private static final double[] DEFAULT_COSTS = {
        0, 3_600, 8_300, 19_000, 28_000, 40_000, 55_000, 360_000, 530_000, 730_000, 990_000, 1_300_000, 1_800_000,
        2_300_000, 3_500_000, 4_300_000, 5_500_000, 7_000_000, 8_500_000, 11_000_000, 14_000_000, 18_000_000,
        23_000_000, 28_000_000, 36_000_000, 46_000_000
    };

    /** a to z, with DEFAULT_COSTS. */
    private void createDefaults() {
        for (char letter = 'a'; letter <= 'z'; letter++) {
            Rank rank = new Rank(String.valueOf(letter));
            rank.setCost(DEFAULT_COSTS[letter - 'a']);
            ranks.put(rank.getId(), rank);
        }
        plugin.getLogger().info("Created ranks.yml with ranks a to z and the default costs.");
    }
}
