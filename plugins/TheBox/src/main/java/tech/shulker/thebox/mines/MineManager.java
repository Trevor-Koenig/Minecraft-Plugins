// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.mines;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.BlockType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns all mines: storage in mines.yml, resetting, and the automatic-reset timer. */
public class MineManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Mine> mines = new TreeMap<>(); // sorted by id, so lists come out alphabetical

    public MineManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mines.yml");
    }

    // ---------------------------------------------------------------- lookup

    /** Mine ids double as permission nodes and YAML keys, so keep them simple. */
    public static boolean isValidId(String id) {
        return id.matches("[a-z0-9_]+");
    }

    public Collection<Mine> getAll() {
        return mines.values();
    }

    public Mine get(String id) {
        return mines.get(id);
    }

    public void add(Mine mine) {
        mines.put(mine.getId(), mine);
        save();
    }

    public void remove(Mine mine) {
        mines.remove(mine.getId());
        save();
    }

    // ---------------------------------------------------------------- resetting

    /** Starts a timer that checks once a second (20 ticks) for mines whose automatic reset is due. */
    public void startResetTimer() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Mine mine : mines.values()) {
                if (mine.isResetDue(now)) {
                    reset(mine);
                }
            }
        }, 20L, 20L);
    }

    /** Moves players out of the mine, then refills it with its block mix. */
    public void reset(Mine mine) {
        // 1. Player safety: anyone inside would end up buried, so move them to the mine's teleport point first.
        for (Player player : mine.getWorld().getPlayers()) {
            if (mine.contains(player.getLocation())) {
                player.teleport(mine.getTeleport());
                player.sendRichMessage("<gray>Mine <white>" + mine.getId() + "</white> is resetting.");
            }
        }

        // 2. Build a WorldEdit pattern that picks each block at random, in proportion to its weight.
        RandomPattern pattern = new RandomPattern();
        mine.getBlocks().forEach((type, weight) ->
            pattern.add(BukkitAdapter.adapt(type.createBlockData()), weight));

        // 3. Fill the region. WorldEdit batches the changes, which is much faster than setting blocks one by one.
        CuboidRegion region = new CuboidRegion(
            BlockVector3.at(mine.getMinX(), mine.getMinY(), mine.getMinZ()),
            BlockVector3.at(mine.getMaxX(), mine.getMaxY(), mine.getMaxZ()));
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(mine.getWorld()))) {
            editSession.setBlocks(region, pattern);
        } catch (MaxChangedBlocksException e) {
            plugin.getLogger().warning("Mine " + mine.getId() + " is larger than WorldEdit's block-change limit: " + e.getMessage());
        }

        mine.scheduleNextReset();
    }

    // ---------------------------------------------------------------- storage

    /** (Re)loads all mines from mines.yml. A missing file just means no mines yet. */
    public void load() {
        mines.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("mines");
        if (section == null) {
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection m = section.getConfigurationSection(id);
            if (m == null || !isValidId(id)) {
                plugin.getLogger().warning("Skipping mine '" + id + "' in mines.yml: invalid id or entry.");
                continue;
            }
            World world = plugin.getServer().getWorld(m.getString("world", ""));
            if (world == null) {
                plugin.getLogger().warning("Skipping mine '" + id + "': world '" + m.getString("world") + "' isn't loaded.");
                continue;
            }

            Mine mine = new Mine(id, world,
                m.getInt("min.x"), m.getInt("min.y"), m.getInt("min.z"),
                m.getInt("max.x"), m.getInt("max.y"), m.getInt("max.z"));

            ConfigurationSection blocks = m.getConfigurationSection("blocks");
            if (blocks != null) {
                for (String key : blocks.getKeys(false)) {
                    NamespacedKey blockKey = NamespacedKey.fromString(key);
                    BlockType type = blockKey == null ? null : Registry.BLOCK.get(blockKey);
                    int weight = blocks.getInt(key);
                    if (type == null || weight <= 0) {
                        plugin.getLogger().warning("Mine '" + id + "': ignoring unknown block or bad weight '" + key + "'.");
                        continue;
                    }
                    mine.getBlocks().put(type, weight);
                }
            }
            if (mine.getBlocks().isEmpty()) {
                plugin.getLogger().warning("Mine '" + id + "' has no valid blocks; defaulting to stone.");
                mine.getBlocks().put(BlockType.STONE, 100);
            }

            mine.setCustomTeleport(m.getLocation("teleport"));
            mine.setResetIntervalSeconds(m.getInt("reset-interval-seconds", 0));
            mines.put(id, mine);
        }
    }

    /** Writes all mines to mines.yml. Called after every change. */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox mines. Edited by /mine commands.",
            "You can also edit this file by hand: run /mine reload afterwards,",
            "otherwise the next /mine change will overwrite your edits."));

        for (Mine mine : mines.values()) {
            String path = "mines." + mine.getId() + ".";
            yaml.set(path + "world", mine.getWorld().getName());
            yaml.set(path + "min.x", mine.getMinX());
            yaml.set(path + "min.y", mine.getMinY());
            yaml.set(path + "min.z", mine.getMinZ());
            yaml.set(path + "max.x", mine.getMaxX());
            yaml.set(path + "max.y", mine.getMaxY());
            yaml.set(path + "max.z", mine.getMaxZ());
            mine.getBlocks().forEach((type, weight) ->
                yaml.set(path + "blocks." + type.key().asString(), weight));
            yaml.set(path + "reset-interval-seconds", mine.getResetIntervalSeconds());
            Location teleport = mine.getCustomTeleport();
            if (teleport != null) {
                yaml.set(path + "teleport", teleport);
            }
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save mines.yml: " + e.getMessage());
        }
    }
}
