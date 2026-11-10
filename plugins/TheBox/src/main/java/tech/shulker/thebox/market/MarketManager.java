// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import tech.shulker.thebox.regions.Box;
import tech.shulker.thebox.regions.RegionClearer;
import tech.shulker.thebox.regions.Regions;
import tech.shulker.thebox.sell.PriceManager;
import tech.shulker.thebox.sell.VaultEconomy;
import tech.shulker.thebox.utils.Durations;
import tech.shulker.thebox.utils.SavedLocation;

/**
 * Owns the markets: storage in markets.yml, each stall's WorldGuard region, renting, the rent timer
 * (auto-renewal, overdue stalls, eviction) and the impound.
 */
public class MarketManager implements Listener {

    /** Stall regions outrank a surrounding region (say, one protecting the whole market), so renters can build. */
    private static final int REGION_PRIORITY = 10;

    private final JavaPlugin plugin;
    private final File file;
    private final Impound impound;
    private final PriceManager prices;
    private final Map<String, Marketplace> markets = new TreeMap<>();
    private ShopHook shops = ShopHook.NONE;

    // Settings, from markets.yml.
    private int maxPrepayDays = 7;
    private int graceDays = 3;
    private int impoundExpiryDays = 30;
    private int maxHelpers = 2;
    /** How long a "day" lasts. 1440 is a real day; a small number makes rent testable in minutes. */
    private int dayLengthMinutes = 1440;

    private boolean warnedNoEconomy = false;

    public MarketManager(JavaPlugin plugin, Impound impound, PriceManager prices) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "markets.yml");
        this.impound = impound;
        this.prices = prices;
    }

    public void setShopHook(ShopHook shops) { this.shops = shops; }
    /** The shop plugin hook (cells use it too, to remove shops when a cell is cleared). */
    public ShopHook getShopHook() { return shops; }
    public Impound getImpound() { return impound; }
    public int getMaxPrepayDays() { return maxPrepayDays; }
    public int getGraceDays() { return graceDays; }
    public int getImpoundExpiryDays() { return impoundExpiryDays; }
    public int getMaxHelpers() { return maxHelpers; }
    public long getDayMillis() { return dayLengthMinutes * 60_000L; }

    // ---------------------------------------------------------------- lookup

    /** Market and stall ids are used in region ids, YAML keys and commands, so keep them simple. */
    public static boolean isValidId(String id) {
        return id.matches("[a-z0-9_]+");
    }

    public Collection<Marketplace> getAll() { return markets.values(); }
    public Marketplace get(String id) { return markets.get(id); }

    /** The stall at this location, in any market, or null. */
    public Stall getStallAt(Location location) {
        for (Marketplace market : markets.values()) {
            for (Stall stall : market.getStalls().values()) {
                if (stall.contains(location)) {
                    return stall;
                }
            }
        }
        return null;
    }

    public List<Stall> getStallsRentedBy(UUID player) {
        List<Stall> rented = new ArrayList<>();
        for (Marketplace market : markets.values()) {
            for (Stall stall : market.getStalls().values()) {
                if (player.equals(stall.getRenter())) {
                    rented.add(stall);
                }
            }
        }
        return rented;
    }

    /** True if the box overlaps any existing stall, in any market. */
    public boolean overlapsAnyStall(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (Marketplace market : markets.values()) {
            for (Stall stall : market.getStalls().values()) {
                if (stall.overlaps(world, minX, minY, minZ, maxX, maxY, maxZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- changes (each one saves)

    public void addMarket(Marketplace market) {
        markets.put(market.getId(), market);
        save();
    }

    public void removeMarket(Marketplace market) {
        for (Stall stall : market.getStalls().values()) {
            removeRegion(stall);
        }
        markets.remove(market.getId());
        save();
    }

    public void addStall(Marketplace market, Stall stall) {
        market.getStalls().put(stall.getId(), stall);
        syncRegion(stall);
        save();
    }

    public void removeStall(Stall stall) {
        removeRegion(stall);
        markets.get(stall.getMarketId()).getStalls().remove(stall.getId());
        save();
    }

    public void startRental(Stall stall, UUID renter, int days) {
        stall.setRenter(renter, System.currentTimeMillis() + days * getDayMillis());
        syncRegion(stall);
        save();
    }

    /** Sets how long rent is paid for. Paying off an overdue stall unfreezes it. */
    public void setPaidUntil(Stall stall, long paidUntil) {
        stall.setPaidUntil(paidUntil);
        if (stall.isFrozen() && paidUntil > System.currentTimeMillis()) {
            stall.setFrozen(false);
            syncRegion(stall);
        }
        save();
    }

    public void setAutoRenew(Stall stall, boolean autoRenew) {
        stall.setAutoRenew(autoRenew);
        save();
    }

    public void addHelper(Stall stall, UUID helper) {
        stall.getHelpers().add(helper);
        syncRegion(stall);
        save();
    }

    public void removeHelper(Stall stall, UUID helper) {
        stall.getHelpers().remove(helper);
        syncRegion(stall);
        save();
    }

    /**
     * Moves everything in the stall into its renter's impound and frees the stall.
     * Returns the number of stacks impounded, or -1 if the stall's world isn't loaded (nothing changes then:
     * freeing a stall without clearing it would hand the old renter's things to the next one).
     */
    public int evict(Stall stall) {
        if (stall.getWorld() == null) {
            return -1;
        }
        UUID renter = stall.getRenter();
        List<ItemStack> items = RegionClearer.clear(stall.getWorld(), boxOf(stall), shops);
        if (renter != null && !items.isEmpty()) {
            impound.add(renter, items);
        }
        stall.setRenter(null, 0);
        syncRegion(stall);
        save();
        return items.size();
    }

    // ---------------------------------------------------------------- WorldGuard

    private static Box boxOf(Stall stall) {
        return new Box(stall.getMinX(), stall.getMinY(), stall.getMinZ(), stall.getMaxX(), stall.getMaxY(), stall.getMaxZ());
    }

    /**
     * Makes the stall's WorldGuard region match the stall (see Regions.sync): its members are the renter and
     * helpers, or nobody while the stall is free or frozen.
     */
    void syncRegion(Stall stall) {
        List<UUID> members = new ArrayList<>();
        if (stall.isRented() && !stall.isFrozen()) {
            members.add(stall.getRenter());
            members.addAll(stall.getHelpers());
        }
        Regions.sync(plugin.getLogger(), stall.getWorld(), stall.getRegionId(), boxOf(stall), REGION_PRIORITY, members, null);
    }

    private void removeRegion(Stall stall) {
        Regions.remove(plugin.getLogger(), stall.getWorld(), stall.getRegionId());
    }

    // ---------------------------------------------------------------- rent timer

    /** Checks rents every 5 seconds, and the impound once a minute. */
    public void startTimer() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkRents, 100L, 100L);
        plugin.getServer().getScheduler().runTaskTimer(plugin,
            () -> impound.expire(impoundExpiryDays * getDayMillis(), prices), 1200L, 1200L);
    }

    private void checkRents() {
        Economy economy = VaultEconomy.find(plugin.getServer());
        if (economy == null) {
            // Without an economy every auto-renewal would fail and every stall would end up evicted. Wait instead.
            if (!warnedNoEconomy) {
                plugin.getLogger().warning("No economy plugin found: market rent is paused until one is installed.");
                warnedNoEconomy = true;
            }
            return;
        }
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Marketplace market : markets.values()) {
            for (Stall stall : market.getStalls().values()) {
                if (!stall.isRented() || stall.getWorld() == null) {
                    continue;
                }
                // Auto-renew one day at a time until rent is paid up or the money runs out.
                // (A loop, so a stall catches up after the server was down for a few days.)
                int renewedDays = 0;
                while (stall.isAutoRenew() && stall.getPaidUntil() <= now && charge(economy, stall.getRenter(), stall.getRentPerDay())) {
                    stall.setPaidUntil(stall.getPaidUntil() + getDayMillis());
                    renewedDays++;
                }
                if (renewedDays > 0) {
                    changed = true;
                    if (stall.isFrozen() && stall.getPaidUntil() > now) {
                        stall.setFrozen(false);
                        syncRegion(stall);
                    }
                    notify(stall.getRenter(), "<gray>Rent for stall <white><stall></white> in </gray><market><gray> renewed: <cost>.",
                        Placeholder.unparsed("cost", economy.format(renewedDays * stall.getRentPerDay())),
                        stallPlaceholder(stall), marketPlaceholder(market));
                }
                if (stall.getPaidUntil() > now) {
                    continue;
                }
                // Overdue.
                long evictAt = stall.getPaidUntil() + graceDays * getDayMillis();
                if (!stall.isFrozen()) {
                    stall.setFrozen(true);
                    syncRegion(stall);
                    changed = true;
                    notify(stall.getRenter(), "<red>Rent for stall <white><stall></white> in </red><market><red> is overdue. Building and its shops are paused. "
                        + "Pay with /market extend within <time>, or everything in it goes to your impound.",
                        stallPlaceholder(stall), marketPlaceholder(market), Placeholder.unparsed("time", Durations.format(evictAt - now)));
                }
                if (now >= evictAt) {
                    UUID renter = stall.getRenter();
                    int stacks = evict(stall);
                    plugin.getLogger().info("Evicted " + plugin.getServer().getOfflinePlayer(renter).getName()
                        + " from stall " + market.getId() + "/" + stall.getId() + " (" + stacks + " stacks impounded).");
                    notify(renter, "<red>Your stall <white><stall></white> in </red><market><red> was cleared for unpaid rent. "
                        + stacks + " stack(s) are waiting for you: /market claim",
                        stallPlaceholder(stall), marketPlaceholder(market));
                }
            }
        }
        if (changed) {
            save();
        }
    }

    private boolean charge(Economy economy, UUID player, double amount) {
        OfflinePlayer offline = plugin.getServer().getOfflinePlayer(player);
        return economy.has(offline, amount) && economy.withdrawPlayer(offline, amount).transactionSuccess();
    }

    // ---------------------------------------------------------------- messages

    /** Sends a message if the player is online. */
    private void notify(UUID player, String template, TagResolver... placeholders) {
        Player online = plugin.getServer().getPlayer(player);
        if (online != null) {
            online.sendRichMessage(template, placeholders);
        }
    }

    static TagResolver stallPlaceholder(Stall stall) {
        return Placeholder.unparsed("stall", stall.getId());
    }

    static TagResolver marketPlaceholder(Marketplace market) {
        // parsed: display names are admin-set MiniMessage, like mine names
        return Placeholder.parsed("market", market.getDisplayName());
    }

    /** Reminds players about overdue stalls and items waiting in their impound. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        for (Stall stall : getStallsRentedBy(player.getUniqueId())) {
            Marketplace market = markets.get(stall.getMarketId());
            if (stall.isFrozen()) {
                long evictAt = stall.getPaidUntil() + graceDays * getDayMillis();
                player.sendRichMessage("<red>Rent for stall <white><stall></white> in </red><market><red> is overdue. "
                    + "Pay with /market extend within <time>, or everything in it goes to your impound.",
                    stallPlaceholder(stall), marketPlaceholder(market), Placeholder.unparsed("time", Durations.format(evictAt - now)));
            } else if (!stall.isAutoRenew() && stall.getPaidUntil() - now < getDayMillis()) {
                player.sendRichMessage("<gold>Rent for stall <white><stall></white> in </gold><market><gold> runs out in <time>.",
                    stallPlaceholder(stall), marketPlaceholder(market), Placeholder.unparsed("time", Durations.format(stall.getPaidUntil() - now)));
            }
        }
        int waiting = impound.count(player.getUniqueId());
        if (waiting > 0) {
            player.sendRichMessage("<gold>You have " + waiting + " stack(s) waiting in your impound: /market claim");
        }
    }

    // ---------------------------------------------------------------- storage

    public void load() {
        markets.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        maxPrepayDays = Math.max(1, yaml.getInt("settings.max-prepay-days", 7));
        graceDays = Math.max(0, yaml.getInt("settings.grace-days", 3));
        impoundExpiryDays = Math.max(1, yaml.getInt("settings.impound-expiry-days", 30));
        maxHelpers = Math.max(0, yaml.getInt("settings.max-helpers", 2));
        dayLengthMinutes = Math.max(1, yaml.getInt("settings.day-length-minutes", 1440));

        ConfigurationSection section = yaml.getConfigurationSection("markets");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection m = section.getConfigurationSection(id);
                if (m == null || !isValidId(id)) {
                    plugin.getLogger().warning("markets.yml: skipping market '" + id + "': invalid id or entry.");
                    continue;
                }
                Marketplace market = new Marketplace(id);
                market.setDisplayName(m.getString("display-name"));
                market.setTeleport(SavedLocation.read(m, "teleport"));
                ConfigurationSection stalls = m.getConfigurationSection("stalls");
                if (stalls != null) {
                    for (String stallId : stalls.getKeys(false)) {
                        Stall stall = loadStall(id, stallId, stalls.getConfigurationSection(stallId));
                        if (stall != null) {
                            market.getStalls().put(stallId, stall);
                        }
                    }
                }
                markets.put(id, market);
            }
        }
        // Make WorldGuard match markets.yml (recreates deleted regions, fixes members after hand edits).
        for (Marketplace market : markets.values()) {
            market.getStalls().values().forEach(this::syncRegion);
        }
    }

    private Stall loadStall(String marketId, String id, ConfigurationSection s) {
        if (s == null || !isValidId(id) || !s.isString("world")) {
            plugin.getLogger().warning("markets.yml: skipping stall '" + marketId + "/" + id + "': invalid id or entry.");
            return null;
        }
        Stall stall = new Stall(marketId, id, s.getString("world"),
            s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"),
            s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"),
            Math.max(0, s.getDouble("rent")));
        String renter = s.getString("renter");
        if (renter != null) {
            try {
                stall.setRenter(UUID.fromString(renter), s.getLong("paid-until"));
                stall.setAutoRenew(s.getBoolean("auto-renew", true));
                stall.setFrozen(s.getBoolean("frozen"));
                for (String helper : s.getStringList("helpers")) {
                    stall.getHelpers().add(UUID.fromString(helper));
                }
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("markets.yml: stall '" + marketId + "/" + id + "' has a bad player id: " + e.getMessage());
            }
        }
        return stall;
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox markets and stalls. Edited by /thebox market commands; renting changes it too.",
            "You can edit it by hand: run /thebox market reload right after, or the next change overwrites your edits.",
            "settings.day-length-minutes: how long a rent \"day\" lasts. Set it to e.g. 2 to test rent, grace and eviction quickly."));
        yaml.set("settings.max-prepay-days", maxPrepayDays);
        yaml.set("settings.grace-days", graceDays);
        yaml.set("settings.impound-expiry-days", impoundExpiryDays);
        yaml.set("settings.max-helpers", maxHelpers);
        yaml.set("settings.day-length-minutes", dayLengthMinutes);
        for (Marketplace market : markets.values()) {
            String path = "markets." + market.getId() + ".";
            if (market.hasDisplayName()) {
                yaml.set(path + "display-name", market.getDisplayName());
            }
            if (market.getTeleport() != null) {
                market.getTeleport().write(yaml, path + "teleport");
            }
            for (Stall stall : market.getStalls().values()) {
                String s = path + "stalls." + stall.getId() + ".";
                yaml.set(s + "world", stall.getWorldName());
                yaml.set(s + "min.x", stall.getMinX());
                yaml.set(s + "min.y", stall.getMinY());
                yaml.set(s + "min.z", stall.getMinZ());
                yaml.set(s + "max.x", stall.getMaxX());
                yaml.set(s + "max.y", stall.getMaxY());
                yaml.set(s + "max.z", stall.getMaxZ());
                yaml.set(s + "rent", stall.getRentPerDay());
                if (stall.isRented()) {
                    yaml.set(s + "renter", stall.getRenter().toString());
                    yaml.set(s + "paid-until", stall.getPaidUntil());
                    yaml.set(s + "auto-renew", stall.isAutoRenew());
                    yaml.set(s + "frozen", stall.isFrozen());
                    yaml.set(s + "helpers", stall.getHelpers().stream().map(UUID::toString).toList());
                }
            }
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save markets.yml: " + e.getMessage());
        }
    }
}
