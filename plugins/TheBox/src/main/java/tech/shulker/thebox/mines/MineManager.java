// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.mines;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.function.pattern.RandomPattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import tech.shulker.thebox.regions.Box;
import tech.shulker.thebox.regions.Regions;
import tech.shulker.thebox.themes.Theme;
import tech.shulker.thebox.themes.ThemeManager;
import tech.shulker.thebox.utils.SavedLocation;

/** Owns all mines: storage in mines.yml, resetting, and the automatic-reset timer. */
public class MineManager implements Listener {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Mine> mines = new TreeMap<>(); // sorted by id, so lists come out alphabetical
    /** mines.yml entries whose world isn't loaded, kept so saving doesn't delete them. */
    private final Map<String, ConfigurationSection> unloadedMines = new TreeMap<>();

    /** Players within this many blocks of a mine are told when it resets. */
    private static final int ANNOUNCE_RADIUS = 32;

    /** Seconds before a reset at which nearby players get a warning in chat. */
    private static final Set<Integer> WARN_AT_SECONDS = Set.of(60, 10, 3, 2, 1);

    /** A percentage reset waits this long, so players get the 10-second warning and the countdown. */
    private static final int PERCENT_RESET_COUNTDOWN_SECONDS = 10;

    private final ThemeManager themes;

    /** TheBox's gravity and safe fall distance modifiers. A modifier's key only has to be unique on its attribute. */
    private final NamespacedKey gravityKey;

    public MineManager(JavaPlugin plugin, ThemeManager themes) {
        this.plugin = plugin;
        this.themes = themes;
        this.gravityKey = new NamespacedKey(plugin, "theme_gravity");
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

    /**
     * The mine's theme: the one set with settheme, otherwise the theme for its PvP level. Null if neither exists, for
     * example after the set theme was removed from themes.yml and no theme has the mine's level.
     */
    public Theme themeOf(Mine mine) {
        Theme set = mine.getTheme() == null ? null : themes.get(mine.getTheme());
        return set != null ? set : themes.forLevel(mine.getLevel());
    }

    /** The mine containing this location, or null if it's in none. */
    public Mine getMineAt(Location location) {
        for (Mine mine : mines.values()) {
            if (mine.contains(location)) {
                return mine;
            }
        }
        return null;
    }

    /** A player's PvP level: the highest level among the mines they can enter (0 if none of them has a level). */
    public int levelOf(Player player) {
        int level = 0;
        for (Mine mine : mines.values()) {
            if (mine.getLevel() > level && player.hasPermission(mine.getPermission())) {
                level = mine.getLevel();
            }
        }
        return level;
    }

    public void add(Mine mine) {
        unloadedMines.remove(mine.getId()); // a new mine with the same id replaces an inactive one
        mines.put(mine.getId(), mine);
        syncRegions(mine);
        save();
    }

    public void remove(Mine mine) {
        mines.remove(mine.getId());
        removeRegions(mine);
        save();
    }

    /** Sets the mine's PvP level, and its area region's pvp flag to match. */
    public void setLevel(Mine mine, int level) {
        mine.setLevel(level);
        syncRegions(mine);
        save();
    }

    /**
     * Gives the mine a new id and saves. Returns false if the id is taken, also by an inactive mine (one whose
     * world isn't loaded): a rename shouldn't silently replace it, the way create does.
     */
    public boolean rename(Mine mine, String newId) {
        if (mines.containsKey(newId) || unloadedMines.containsKey(newId)) {
            return false;
        }
        removeRegions(mine); // WorldGuard regions can't be renamed: drop the old ids, sync under the new ones
        mines.remove(mine.getId());
        mine.setId(newId);
        mines.put(newId, mine);
        syncRegions(mine);
        save();
        return true;
    }

    // ---------------------------------------------------------------- WorldGuard regions

    /** Like stalls and cells, so a mine's regions win over a bigger region around them (a mine hall, say). */
    private static final int REGION_PRIORITY = 10;

    /**
     * Makes the mine's two WorldGuard regions match it (see Regions.sync), creating any that are missing:
     * <ul>
     *   <li>The mine region, exactly the mine: players can break blocks (placing stays protected) and no mobs spawn.
     *       Its pvp flag is cleared, because older versions of the README had admins set it there by hand.</li>
     *   <li>The area region, the mine plus AREA_MARGIN blocks: PvP once the mine has a level. Passthrough makes it
     *       a flags-only region, so it never changes who can build, say in a cell it overlaps.</li>
     * </ul>
     */
    private void syncRegions(Mine mine) {
        Regions.sync(plugin.getLogger(), mine.getWorld(), mine.getRegionId(), mine.getBox(), REGION_PRIORITY, List.of(),
            region -> {
                region.setPriority(REGION_PRIORITY);
                region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.ALLOW);
                region.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
                region.setFlag(Flags.PVP, null);
            });
        Regions.sync(plugin.getLogger(), mine.getWorld(), mine.getAreaRegionId(), mine.getAreaBox(), REGION_PRIORITY, List.of(),
            region -> {
                region.setPriority(REGION_PRIORITY);
                region.setFlag(Flags.PASSTHROUGH, StateFlag.State.ALLOW);
                region.setFlag(Flags.PVP, mine.getLevel() > 0 ? StateFlag.State.ALLOW : null);
            });
    }

    private void removeRegions(Mine mine) {
        Regions.remove(plugin.getLogger(), mine.getWorld(), mine.getRegionId());
        Regions.remove(plugin.getLogger(), mine.getWorld(), mine.getAreaRegionId());
    }

    /**
     * Other WorldGuard regions (not mines') that the mine's area overlaps. Once the mine has a level, players at that
     * level can fight there too: the area's priority beats a lower region's pvp deny, such as spawn's.
     */
    public List<String> regionsInArea(Mine mine) {
        RegionManager manager = Regions.manager(mine.getWorld());
        if (manager == null) {
            return List.of();
        }
        Set<String> mineRegions = new HashSet<>();
        for (Mine other : mines.values()) {
            mineRegions.add(other.getRegionId());
            mineRegions.add(other.getAreaRegionId());
        }
        Box area = mine.getAreaBox();
        List<String> overlapping = new ArrayList<>();
        ProtectedRegion probe = new ProtectedCuboidRegion("thebox_probe", area.min(), area.max());
        for (ProtectedRegion region : manager.getApplicableRegions(probe).getRegions()) {
            if (!mineRegions.contains(region.getId()) && !region.getId().equals(ProtectedRegion.GLOBAL_REGION)) {
                overlapping.add(region.getId());
            }
        }
        overlapping.sort(null);
        return overlapping;
    }

    // ---------------------------------------------------------------- resetting

    /**
     * Starts a timer that runs once a second (20 ticks): it resets mines whose reset is due, and sends
     * countdown warnings (1 minute, 10 seconds, 3, 2, 1) to players near mines about to reset.
     */
    public void startResetTimer() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            for (Mine mine : mines.values()) {
                if (mine.isResetDue(now)) {
                    reset(mine);
                } else if (mine.isResetScheduled()) {
                    // Rounded up, so "3" means 2-3 seconds left. The timer can run a little late under lag,
                    // so lastWarning makes sure no warning is sent twice.
                    int secondsLeft = (int) ((mine.getNextResetMillis() - now + 999) / 1000);
                    if (WARN_AT_SECONDS.contains(secondsLeft) && mine.getLastWarning() != secondsLeft) {
                        mine.setLastWarning(secondsLeft);
                        warn(mine, secondsLeft);
                    }
                }
            }
        }, 20L, 20L);
    }

    private void warn(Mine mine, int secondsLeft) {
        String when = secondsLeft == 60 ? "in 1 minute."
            : secondsLeft > 3 ? "in " + secondsLeft + " seconds."
            : "in <red>" + secondsLeft + "</red>...";
        for (Player player : playersNear(mine)) {
            player.sendRichMessage("<gold>Mine <white><name></white> resets " + when,
                Placeholder.parsed("name", mine.getDisplayName()));
        }
    }

    /** Players inside the mine or within ANNOUNCE_RADIUS blocks of it. */
    private Set<Player> playersNear(Mine mine) {
        Set<Player> near = new HashSet<>();
        for (Player player : mine.getWorld().getPlayers()) {
            if (mine.isNear(player.getLocation(), ANNOUNCE_RADIUS)) {
                near.add(player);
            }
        }
        return near;
    }

    /** Moves players out of the mine, then refills it with its block mix. */
    public void reset(Mine mine) {
        // 1. Player safety: anyone inside would end up buried, so lift them straight up onto the mine's top
        //    first. Remember who was near the mine so they can be told about the reset afterwards.
        Set<Player> toNotify = playersNear(mine);
        for (Player player : toNotify) {
            if (mine.contains(player.getLocation())) {
                player.teleport(above(mine, player.getLocation()));
            }
        }

        // 2. Build a WorldEdit pattern that picks each block at random, in proportion to its weight,
        //    with the surface block on the faces players can see, unless the surface is off.
        RandomPattern mix = new RandomPattern();
        mine.getBlocks().forEach((type, weight) ->
            mix.add(BukkitAdapter.adapt(type.createBlockData()), weight));
        BlockType surface = mine.getSurfaceBlock();
        Pattern pattern = surface == null ? mix : withSurface(mine, mix, surface);

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

        // 4. Tell players who were in or around the mine: one of its theme's reset messages if it has any.
        Theme theme = themeOf(mine);
        Component message = theme == null ? null : themes.resetMessage(theme, MiniMessage.miniMessage().deserialize(mine.getDisplayName()));
        for (Player player : toNotify) {
            if (message != null) {
                player.sendMessage(message);
            } else {
                player.sendRichMessage("<gray>Mine <white><name></white> has been reset.",
                    Placeholder.parsed("name", mine.getDisplayName()));
            }
        }
    }

    /**
     * The mine's mix, but with its surface block on every edge block players could see: one whose neighbour just
     * outside the mine is see-through (air, glass, a fence...). In a walled pit that's only the top; a mine with glass
     * sides gets its sides covered too. Ores showing there would be free for whoever arrives first, which is why the
     * classic prison plugins had a surface option. Found before the fill, which never changes blocks outside the mine.
     */
    private static Pattern withSurface(Mine mine, Pattern mix, BlockType surfaceType) {
        World world = mine.getWorld();
        Set<BlockVector3> exposed = new HashSet<>();
        for (int x = mine.getMinX(); x <= mine.getMaxX(); x++) {
            for (int z = mine.getMinZ(); z <= mine.getMaxZ(); z++) {
                addIfSeen(world, exposed, x, mine.getMaxY(), z, 0, 1, 0);  // top
                addIfSeen(world, exposed, x, mine.getMinY(), z, 0, -1, 0); // bottom
            }
        }
        for (int y = mine.getMinY(); y <= mine.getMaxY(); y++) {
            for (int z = mine.getMinZ(); z <= mine.getMaxZ(); z++) {
                addIfSeen(world, exposed, mine.getMinX(), y, z, -1, 0, 0);
                addIfSeen(world, exposed, mine.getMaxX(), y, z, 1, 0, 0);
            }
            for (int x = mine.getMinX(); x <= mine.getMaxX(); x++) {
                addIfSeen(world, exposed, x, y, mine.getMinZ(), 0, 0, -1);
                addIfSeen(world, exposed, x, y, mine.getMaxZ(), 0, 0, 1);
            }
        }
        BaseBlock surface = BukkitAdapter.adapt(surfaceType.createBlockData()).toBaseBlock();
        // Pattern has no abstract method in WorldEdit 7.4, so it can't be a lambda; applyBlock is the one to override.
        return new Pattern() {
            @Override
            public BaseBlock applyBlock(BlockVector3 position) {
                return exposed.contains(position) ? surface : mix.applyBlock(position);
            }
        };
    }

    /** Adds the mine block at x y z if its neighbour in direction dx dy dz, just outside the mine, is see-through. */
    private static void addIfSeen(World world, Set<BlockVector3> exposed, int x, int y, int z, int dx, int dy, int dz) {
        if (!world.getBlockAt(x + dx, y + dy, z + dz).getBlockData().isOccluding()) {
            exposed.add(BlockVector3.at(x, y, z));
        }
    }

    /**
     * The spot straight above {@code location}, standing on the mine's top layer, facing the same way.
     * Falls back to the mine's teleport point if something is built there (a roof over the mine, say),
     * so nobody is lifted into solid blocks.
     */
    private static Location above(Mine mine, Location location) {
        Location up = location.clone();
        up.setY(mine.getMaxY() + 1);
        boolean clear = up.getBlock().isPassable() && up.clone().add(0, 1, 0).getBlock().isPassable();
        return clear ? up : mine.getTeleport();
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
            if (mine.isPercentResetDue() && !mine.isResetTriggered()) {
                // Don't reset now: start a short countdown, and the reset timer takes it from there
                // (warnings, then the reset itself).
                mine.triggerReset(PERCENT_RESET_COUNTDOWN_SECONDS);
            }
            return;
        }
    }

    /**
     * Keeps players out of mines they haven't unlocked. PlayerMoveEvent fires many times a second per player, so
     * bail out early unless the player moved into a different block. Teleports don't reach this handler
     * (see onTeleport). Players already inside a mine can always walk out.
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
        updateNightVision(player, to);
        updateGravity(player, to);
    }

    /**
     * PlayerTeleportEvent extends PlayerMoveEvent but has its own HandlerList, and Bukkit only calls the
     * handlers registered on the fired event's own list. So teleports (ender pearls, /tpa, /home) need their
     * own handler to be checked the same way.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        onMove(event);
    }

    // ---------------------------------------------------------------- night vision

    /** Infinite, so it never flickers, and that's also how TheBox recognises its own: potions always run out. */
    private static final PotionEffect MINE_NIGHT_VISION =
        new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, true, false, true);

    /** True for players who have Night Vision from their rank (EffectPerks), which leaving a mine mustn't take away. */
    private Predicate<Player> nightVisionPerk = player -> false;

    public void setNightVisionPerk(Predicate<Player> nightVisionPerk) {
        this.nightVisionPerk = nightVisionPerk;
    }

    /** True if the player is in a mine's area (the mine plus Mine.AREA_MARGIN blocks), where mines give Night Vision. */
    public boolean isInMineArea(Player player) {
        return isNearAnyMine(player.getLocation(), Mine.AREA_MARGIN);
    }

    /**
     * Mines get dark below the surface, so players in a mine's area have Night Vision. The edge is Mine.AREA_MARGIN
     * blocks out, not the mine box itself: there, walking along the rim or hopping in and out of a hole would flash it
     * on and off. Only TheBox's own is taken away: a Night Vision potion keeps running, and TheBox's takes over when
     * it ends. Players with Night Vision from their rank keep it. Checked on every block-to-block move, so it also
     * comes back after milk or a death.
     */
    private void updateNightVision(Player player, Location location) {
        PotionEffect current = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        boolean near = isNearAnyMine(location, Mine.AREA_MARGIN);
        if (near && current == null) {
            player.addPotionEffect(MINE_NIGHT_VISION);
        } else if (!near && current != null && current.isInfinite() && !nightVisionPerk.test(player)) {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    private boolean isNearAnyMine(Location location, int margin) {
        for (Mine mine : mines.values()) {
            if (mine.isNear(location, margin)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Effects are saved with the player, so someone who logged out in a mine would otherwise keep Night Vision forever.
     * Gravity is the other way round: it isn't saved, so someone who logs in on an asteroid needs it back.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateNightVision(event.getPlayer(), event.getPlayer().getLocation());
        updateGravity(event.getPlayer(), event.getPlayer().getLocation());
    }

    /**
     * Respawning isn't a teleport, so onMove never sees the jump from where the player died to their respawn point.
     * Paper's PlayerPostRespawnEvent comes once they're there.
     */
    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent event) {
        updateNightVision(event.getPlayer(), event.getRespawnedLocation());
        updateGravity(event.getPlayer(), event.getRespawnedLocation());
    }

    // ---------------------------------------------------------------- theme gravity

    /**
     * Within its theme's margin of a mine, players get the theme's gravity and safe fall distance (Starfall's low
     * gravity). The modifiers are transient, so they're never saved with the player and a crash can't leave them on.
     * Checked on every block-to-block move, like Night Vision.
     */
    private void updateGravity(Player player, Location location) {
        Theme.Gravity gravity = gravityAt(location);
        AttributeInstance safeFall = player.getAttribute(Attribute.SAFE_FALL_DISTANCE);
        setGravityModifier(player.getAttribute(Attribute.GRAVITY), AttributeModifier.Operation.MULTIPLY_SCALAR_1,
            gravity == null ? 0 : gravity.multiplier() - 1);
        // The setting is a total, so the modifier adds the difference from the player's base value (normally 3).
        setGravityModifier(safeFall, AttributeModifier.Operation.ADD_NUMBER,
            gravity == null || safeFall == null ? 0 : gravity.safeFallDistance() - safeFall.getBaseValue());
    }

    /**
     * The gravity at this location: that of a theme which changes gravity, if the location is within its margin of
     * one of its mines. Null for normal gravity. Where areas overlap, a theme that changes gravity wins.
     */
    private Theme.Gravity gravityAt(Location location) {
        for (Mine mine : mines.values()) {
            Theme theme = themeOf(mine);
            if (theme != null && !theme.gravity().equals(Theme.Gravity.NORMAL)
                    && mine.isNear(location, theme.margin())) {
                return theme.gravity();
            }
        }
        return null;
    }

    /**
     * Gives the attribute TheBox's modifier with this amount, or removes it for 0. Does nothing if it's already right:
     * every change is sent to the player's client, and this runs on every step.
     */
    private void setGravityModifier(AttributeInstance attribute, AttributeModifier.Operation operation, double amount) {
        if (attribute == null) {
            return;
        }
        AttributeModifier current = attribute.getModifier(gravityKey);
        if (current != null && current.getAmount() == amount && current.getOperation() == operation) {
            return;
        }
        if (current != null) {
            attribute.removeModifier(gravityKey);
        }
        if (amount != 0) {
            // Adding a key the attribute already has throws, hence the removal above.
            attribute.addTransientModifier(new AttributeModifier(gravityKey, amount, operation));
        }
    }

    /**
     * Takes TheBox's gravity off every online player. For when TheBox is disabled (a plugin reload) and players stay
     * online: nothing would ever take it off them otherwise.
     */
    public void clearGravity() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            setGravityModifier(player.getAttribute(Attribute.GRAVITY), AttributeModifier.Operation.MULTIPLY_SCALAR_1, 0);
            setGravityModifier(player.getAttribute(Attribute.SAFE_FALL_DISTANCE), AttributeModifier.Operation.ADD_NUMBER, 0);
        }
    }

    // ---------------------------------------------------------------- PvP in mines

    /**
     * The mine whose PvP rule stops this attacker hurting this victim, or null if none does. In a mine's area, PvP
     * only happens between players at the mine's level; level 0 means no PvP (see design.md, PvP). Inside a mine, that
     * mine decides. Around mines, where two areas can overlap, every mine whose area it is must allow the hit. The
     * rule only ever takes PvP away: WorldGuard separately decides where PvP is possible at all.
     */
    private Mine blockingMine(Player attacker, Player victim) {
        if (attacker == victim) {
            return null; // hurting yourself (your own arrow or potion) works as in vanilla
        }
        Location location = victim.getLocation();
        Mine inside = getMineAt(location);
        if (inside != null) {
            return blocks(inside, attacker, victim) ? inside : null;
        }
        for (Mine mine : mines.values()) {
            if (mine.isInArea(location) && blocks(mine, attacker, victim)) {
                return mine;
            }
        }
        return null; // away from mines, WorldGuard's rules are all there is
    }

    private boolean blocks(Mine mine, Player attacker, Player victim) {
        int level = mine.getLevel();
        return level == 0 || levelOf(attacker) != level || levelOf(victim) != level;
    }

    /** Hits: melee, arrows, tridents, splash potions of harming. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || !(event.getDamageSource().getCausingEntity() instanceof Player attacker)) {
            return;
        }
        Mine mine = blockingMine(attacker, victim);
        if (mine != null) {
            event.setCancelled(true);
            attacker.sendActionBar(MiniMessage.miniMessage().deserialize(mine.getLevel() == 0
                ? "<red>There's no PvP at this mine."
                : "<red>Only players at this mine's level can fight here."));
        }
    }

    /** Splash potions with a harmful effect (poison, weakness, …), which never fire a damage event: protected players aren't affected. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player attacker) || !hasHarmfulEffect(event.getPotion().getEffects())) {
            return;
        }
        for (LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim && blockingMine(attacker, victim) != null) {
                event.setIntensity(victim, 0);
            }
        }
    }

    /** Lingering potion clouds, checked each time they apply (every 5 ticks), the same way as splash potions. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLingeringCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player attacker)) {
            return;
        }
        List<PotionEffect> effects = new ArrayList<>();
        if (cloud.getBasePotionType() != null) {
            effects.addAll(cloud.getBasePotionType().getPotionEffects());
        }
        if (cloud.hasCustomEffects()) {
            effects.addAll(cloud.getCustomEffects());
        }
        if (hasHarmfulEffect(effects)) {
            event.getAffectedEntities().removeIf(entity ->
                entity instanceof Player victim && blockingMine(attacker, victim) != null);
        }
    }

    private static boolean hasHarmfulEffect(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- storage

    /** (Re)loads all mines from mines.yml. A missing file just means no mines yet. */
    public void load() {
        mines.clear();
        unloadedMines.clear();
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
                // Keep it as-is so the next save writes it back: skipping it would delete it from mines.yml.
                plugin.getLogger().warning("Mine '" + id + "' is inactive: world '" + m.getString("world") + "' isn't loaded. It's kept in mines.yml.");
                unloadedMines.put(id, m);
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
                    double weight = blocks.getDouble(key); // older files have whole numbers, which read fine
                    if (type == null || weight <= 0) {
                        plugin.getLogger().warning("Mine '" + id + "': ignoring unknown block or bad weight '" + key + "'.");
                        continue;
                    }
                    mine.getBlocks().put(type, weight);
                }
            }
            if (mine.getBlocks().isEmpty()) {
                plugin.getLogger().warning("Mine '" + id + "' has no valid blocks; defaulting to stone.");
                mine.getBlocks().put(BlockType.STONE, 100.0);
            }

            SavedLocation teleport = SavedLocation.read(m, "teleport");
            // Older mines.yml files stored a Bukkit Location here; getLocation still reads those.
            mine.setCustomTeleport(teleport != null ? teleport.toLocation() : m.getLocation("teleport"));
            mine.setResetIntervalSeconds(m.getInt("reset-interval-seconds", 0));
            mine.setResetPercent(Math.max(0, Math.min(100, m.getInt("reset-percent", 0))));
            mine.setLevel(Math.max(0, m.getInt("level", 0)));
            mine.setTheme(m.getString("theme")); // missing: the theme of its level
            mine.setDisplayName(m.getString("display-name"));
            // Missing means the mix's most common block; "none" turns it off.
            String surface = m.getString("surface");
            if ("none".equals(surface)) {
                mine.turnSurfaceOff();
            } else if (surface != null) {
                NamespacedKey surfaceKey = NamespacedKey.fromString(surface);
                BlockType type = surfaceKey == null ? null : Registry.BLOCK.get(surfaceKey);
                if (type == null) {
                    plugin.getLogger().warning("Mine '" + id + "': unknown surface block '" + surface + "'; using the mix's most common block.");
                }
                mine.setSurface(type);
            }
            mines.put(id, mine);
        }

        // Syncing at load also takes over mine_<id> regions that admins made by hand before TheBox managed them.
        // Overlaps are checked once every mine is known, so other mines' regions aren't reported.
        for (Mine mine : mines.values()) {
            syncRegions(mine);
        }
        for (Mine mine : mines.values()) {
            List<String> overlapping = mine.getLevel() > 0 ? regionsInArea(mine) : List.of();
            if (!overlapping.isEmpty()) {
                plugin.getLogger().warning("Mine " + mine.getId() + "'s PvP area overlaps " + String.join(", ", overlapping)
                    + ": players at level " + mine.getLevel() + " can fight there too (README section 9, step 4).");
            }
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
            // Whole weights are written as whole numbers (82, not 82.0), so the file stays easy to edit by hand.
            mine.getBlocks().forEach((type, weight) ->
                yaml.set(path + "blocks." + type.key().asString(), weight % 1 == 0 ? (Object) weight.intValue() : weight));
            yaml.set(path + "reset-interval-seconds", mine.getResetIntervalSeconds());
            yaml.set(path + "reset-percent", mine.getResetPercent());
            yaml.set(path + "level", mine.getLevel());
            if (mine.getTheme() != null) {
                yaml.set(path + "theme", mine.getTheme());
            }
            if (mine.isSurfaceOff()) {
                yaml.set(path + "surface", "none");
            } else if (mine.getSurface() != null) {
                yaml.set(path + "surface", mine.getSurface().key().asString());
            }
            if (mine.hasDisplayName()) {
                yaml.set(path + "display-name", mine.getDisplayName());
            }
            Location teleport = mine.getCustomTeleport();
            if (teleport != null) {
                // Plain values, not a Bukkit Location: a Location can't be read back if its world loads late.
                SavedLocation.of(teleport).write(yaml, path + "teleport");
            }
        }
        // Mines whose world wasn't loaded: written back unchanged.
        unloadedMines.forEach((id, section) -> section.getValues(true).forEach((key, value) -> {
            if (!(value instanceof ConfigurationSection)) {
                yaml.set("mines." + id + "." + key, value);
            }
        }));

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save mines.yml: " + e.getMessage());
        }
    }
}
