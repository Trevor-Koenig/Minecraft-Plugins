// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.effects;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Effects that ranks unlock, like Night Vision and Speed. A player has one for as long as they have its permission and
 * haven't switched it off in /effects. The permission is thebox.effect.(key) for an effect with one level, and
 * thebox.effect.(key).(level) otherwise (thebox.effect.speed.2), so ranks grant them like any other unlock.
 *
 * TheBox's effects are infinite, which is how it recognises its own: potions always run out. A potion of the same effect
 * still works, and when it ends, Minecraft brings back the weaker infinite effect it kept underneath.
 */
public class EffectPerks implements Listener {

    /** An effect a rank can give. maxLevel 1 means its permission has no level number. The icon is for /effects. */
    public record Perk(String key, PotionEffectType type, int maxLevel, String name, ItemType icon) {

        public String permission(int level) {
            return "thebox.effect." + key + (maxLevel == 1 ? "" : "." + level);
        }

        /** "Night Vision", "Speed II". */
        public String name(int level) {
            return maxLevel == 1 ? name : name + " " + roman(level);
        }
    }

    /**
     * The effects ranks can give; adding one is one line here. Haste and Jump Boost are left out on purpose (design doc,
     * Rank rewards): Haste skips part of the pickaxe ramp, and Jump Boost clears fences and walls. The icons are each
     * potion's brewing ingredient.
     */
    public static final List<Perk> PERKS = List.of(
        new Perk("night_vision", PotionEffectType.NIGHT_VISION, 1, "Night Vision", ItemType.GOLDEN_CARROT),
        new Perk("speed", PotionEffectType.SPEED, 2, "Speed", ItemType.SUGAR));

    private final JavaPlugin plugin;
    /** True while something else in TheBox gives the player this effect (a mine's Night Vision), so it stays. */
    private final BiPredicate<Player, PotionEffectType> givenElsewhere;

    public EffectPerks(JavaPlugin plugin, BiPredicate<Player, PotionEffectType> givenElsewhere) {
        this.plugin = plugin;
        this.givenElsewhere = givenElsewhere;
    }

    /** The perk with this key, or null. */
    public static Perk find(String key) {
        for (Perk perk : PERKS) {
            if (perk.key().equals(key)) {
                return perk;
            }
        }
        return null;
    }

    /** The highest level of this effect the player has unlocked, or 0. */
    public int unlockedLevel(Player player, Perk perk) {
        for (int level = perk.maxLevel(); level >= 1; level--) {
            if (player.hasPermission(perk.permission(level))) {
                return level;
            }
        }
        return 0;
    }

    /** Whether the player has this effect switched on (it is, until they switch it off). */
    public boolean isOn(Player player, Perk perk) {
        return !player.getPersistentDataContainer().has(offKey(perk));
    }

    /** True if the player has Night Vision unlocked and on. MineManager asks, so leaving a mine doesn't take it away. */
    public boolean hasNightVision(Player player) {
        Perk perk = find("night_vision");
        return perk != null && isOn(player, perk) && unlockedLevel(player, perk) > 0;
    }

    /**
     * Switches the effect on or off, then updates the player's effects. Returns true if it's now on. The choice is kept
     * in the player's persistent data container, which Minecraft saves with the rest of their player data.
     */
    public boolean toggle(Player player, Perk perk) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        boolean on = !isOn(player, perk);
        if (on) {
            data.remove(offKey(perk));
        } else {
            data.set(offKey(perk), PersistentDataType.BOOLEAN, true);
        }
        refresh(player);
        return on;
    }

    /** Gives the player the effects they should have, and takes away TheBox's ones they shouldn't. */
    public void refresh(Player player) {
        for (Perk perk : PERKS) {
            int level = isOn(player, perk) ? unlockedLevel(player, perk) : 0;
            PotionEffect current = player.getPotionEffect(perk.type());
            boolean ours = current != null && current.isInfinite();
            if (level == 0) {
                if (ours && !givenElsewhere.test(player, perk.type())) {
                    player.removePotionEffect(perk.type());
                }
                continue;
            }
            int amplifier = level - 1; // amplifier 0 is level I
            if (ours && current.getAmplifier() > amplifier) {
                player.removePotionEffect(perk.type()); // they lost a level: prestige, or an admin's change
                current = null;
            }
            if (current == null || current.getAmplifier() < amplifier) {
                // Ambient (faint, like a beacon's) with no particles, but still shown in the inventory and HUD.
                player.addPotionEffect(new PotionEffect(perk.type(), PotionEffect.INFINITE_DURATION, amplifier, true, false, true));
            }
        }
    }

    /**
     * Rank-ups, prestiges and admins' LuckPerms commands all end with LuckPerms recalculating the player's permissions,
     * so that's when effects are brought up to date. LuckPerms fires its events on whichever thread made the change,
     * and the Bukkit API is main-thread only, so the update is scheduled onto the main thread.
     */
    public void watch(LuckPerms luckPerms) {
        luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
            UUID uuid = event.getUser().getUniqueId();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(uuid);
                if (player != null) {
                    refresh(player);
                }
            });
        });
    }

    // ---------------------------------------------------------------- when effects go missing

    /** Effects are saved with the player, so this also takes away ones they lost the rank for while offline. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    /** Dying clears effects. PlayerRespawnEvent is too early to add them back: it fires before the player respawns. */
    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent event) {
        refresh(event.getPlayer());
    }

    /**
     * Milk and totems clear effects, and a stronger potion running out may leave none. TheBox's are put back a tick
     * later, once the clearing is done.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffectGone(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || (event.getAction() != EntityPotionEffectEvent.Action.REMOVED && event.getAction() != EntityPotionEffectEvent.Action.CLEARED)) {
            return;
        }
        EntityPotionEffectEvent.Cause cause = event.getCause();
        if (cause != EntityPotionEffectEvent.Cause.MILK && cause != EntityPotionEffectEvent.Cause.TOTEM
                && cause != EntityPotionEffectEvent.Cause.EXPIRATION) {
            return;
        }
        if (PERKS.stream().anyMatch(perk -> perk.type().equals(event.getModifiedType()))) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    refresh(player);
                }
            });
        }
    }

    // ---------------------------------------------------------------- helpers

    private NamespacedKey offKey(Perk perk) {
        return new NamespacedKey(plugin, "effect_off." + perk.key());
    }

    static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
