// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.sell;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Each player's lifetime /sell earnings, for /sell top and the %thebox_earned% placeholder, stored in earnings.yml.
 * Only /sell counts: it's the only money the server creates. /pay and shop sales just move money between players,
 * so counting them would let two friends pass the same money back and forth to climb the board.
 */
public class Earnings {

    /** One player's total, with the name they had when they last sold, so the board can show offline players. */
    public record Entry(UUID uuid, String name, double earned) {}

    private final JavaPlugin plugin;
    private final File file;
    // Changed only by /sell, on the main thread. Concurrent because placeholder plugins (ajLeaderboards,
    // holograms) read it from their own threads.
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public Earnings(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "earnings.yml");
    }

    /** Adds a /sell payout to the player's total, and saves. */
    public void add(Player player, double amount) {
        UUID uuid = player.getUniqueId();
        entries.put(uuid, new Entry(uuid, player.getName(), get(uuid) + amount));
        save();
    }

    /** The player's lifetime total: 0 if they've never sold anything. */
    public double get(UUID uuid) {
        Entry entry = entries.get(uuid);
        return entry == null ? 0 : entry.earned();
    }

    /** Everyone who has sold something, highest total first. */
    public List<Entry> ranked() {
        return entries.values().stream()
            .sorted(Comparator.comparingDouble(Entry::earned).reversed())
            .toList();
    }

    public void load() {
        entries.clear();
        ConfigurationSection section = YamlConfiguration.loadConfiguration(file).getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("earnings.yml: skipping '" + key + "', which isn't a player UUID.");
                continue;
            }
            entries.put(uuid, new Entry(uuid, section.getString(key + ".name", "?"), section.getDouble(key + ".earned")));
        }
    }

    /** Writes every total to earnings.yml. Called after every sale; the file stays small (one entry per player). */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox: each player's lifetime /sell earnings, by UUID, for /sell top and %thebox_earned%.",
            "name is the player's name when they last sold. For a new season, delete this file with the server stopped."));
        for (Entry entry : entries.values()) {
            String path = "players." + entry.uuid() + ".";
            yaml.set(path + "name", entry.name());
            yaml.set(path + "earned", entry.earned());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save earnings.yml: " + e.getMessage());
        }
    }
}
