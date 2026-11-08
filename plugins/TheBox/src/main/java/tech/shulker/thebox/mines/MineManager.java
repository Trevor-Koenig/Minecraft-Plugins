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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.BlockType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns all mines: storage in mines.yml, resetting, and the automatic-reset timer. */
public class MineManager implements Listener {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Mine> mines = new TreeMap<>(); // sorted by id, so lists come out alphabetical
    private final Set<Mine> pendingResets = new HashSet<>();  // percentage resets queued for the next tick

    /** Players within this many blocks of a mine are told when it resets. */
    private static final int ANNOUNCE_RADIUS = 32;

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
        //    Remember who was near the mine (or moved out of it) so they can be told about the reset afterwards.
        Set<Player> toNotify = new HashSet<>();
        for (Player player : mine.getWorld().getPlayers()) {
            if (mine.contains(player.getLocation())) {
                player.teleport(mine.getTeleport());
                toNotify.add(player);
            } else if (mine.isNear(player.getLocation(), ANNOUNCE_RADIUS)) {
                toNotify.add(player);
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

        mine.clearBlocksMined();
        mine.scheduleNextReset();

        // 4. Tell players who were in or around the mine. Placeholder.parsed keeps the display name's own tags contained.
        for (Player player : toNotify) {
            player.sendRichMessage("<gray>Mine <white><name></white> has been reset.",
                Placeholder.parsed("name", mine.getDisplayName()));
        }
    }

    /** Counts blocks broken inside mines and triggers percentage resets. Registered as a listener in TheBox. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location location = event.getBlock().getLocation();
        for (Mine mine : mines.values()) {
            if (!mine.contains(location)) {
                continue;
            }
            mine.addBlockMined();
            if (mine.isPercentResetDue() && pendingResets.add(mine)) {
                // Reset on the next tick, not now: this break hasn't finished yet, so resetting immediately
                // would refill the mine and then this break would still punch a hole in it.
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pendingResets.remove(mine);
                    if (mines.get(mine.getId()) == mine) { // skip if the mine was deleted or reloaded meanwhile
                        reset(mine);
                    }
                });
            }
            return;
        }
    }

    /**
     * Keeps players out of mines they haven't unlocked. PlayerMoveEvent fires many times a second per player, so
     * bail out early unless the player moved into a different block. Teleports fire this event too
     * (PlayerTeleportEvent extends PlayerMoveEvent), so teleporting into a locked mine is blocked as well.
     * Players already inside a mine can always walk out.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) {
            return;
        }
        Player player = event.getPlayer();
        for (Mine mine : mines.values()) {
            if (mine.contains(to) && !mine.contains(from) && !player.hasPermission(mine.getPermission())) {
                event.setCancelled(true);
                player.sendActionBar(MiniMessage.miniMessage().deserialize(
                    "<red>You haven't unlocked </red><name><red> yet.",
                    Placeholder.parsed("name", mine.getDisplayName())));
                return;
            }
        }
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
            mine.setResetPercent(Math.max(0, Math.min(100, m.getInt("reset-percent", 0))));
            mine.setDisplayName(m.getString("display-name"));
            mines.put(id, mine);
        }
    }

    /** Writes all mines to mines.yml. Called after every change. */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox mines. Edited by /thebox mine commands.",
            "You can also edit this file by hand: run /thebox mine reload afterwards,",
            "otherwise the next /thebox mine change will overwrite your edits."));

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
            yaml.set(path + "reset-percent", mine.getResetPercent());
            if (mine.hasDisplayName()) {
                yaml.set(path + "display-name", mine.getDisplayName());
            }
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
