// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.themes;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The themes in themes.yml: each PvP band's name, title and reset messages. The first start writes the six default
 * sites, which admins can then change or replace. Mines pick their theme by PvP level (MineManager.themeOf).
 */
public class ThemeManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Theme> themes = new LinkedHashMap<>(); // in file order, which is ladder order

    public ThemeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "themes.yml");
    }

    public Collection<Theme> getAll() {
        return themes.values();
    }

    /** The theme with this id, or null. */
    public Theme get(String id) {
        return themes.get(id);
    }

    /** The theme whose band includes this rank, or null. */
    public Theme forRank(String rankId) {
        for (Theme theme : themes.values()) {
            if (theme.ranks().contains(rankId)) {
                return theme;
            }
        }
        return null;
    }

    /** The theme for mines of this PvP level, or null. */
    public Theme forLevel(int level) {
        for (Theme theme : themes.values()) {
            if (theme.level() == level) {
                return theme;
            }
        }
        return null;
    }

    /** Shows the theme's title and subtitle and plays its sound: what a player sees on arriving in a new band. */
    public void showTitle(Player player, Theme theme) {
        player.showTitle(Title.title(
            MiniMessage.miniMessage().deserialize(theme.title()),
            MiniMessage.miniMessage().deserialize(theme.subtitle()),
            Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))));
        if (theme.sound() != null) {
            player.playSound(Sound.sound(theme.sound(), Sound.Source.MASTER, 1f, 1f));
        }
    }

    /** One of the theme's reset messages at random, with the mine's name in it, or null if it has none. */
    public Component resetMessage(Theme theme, Component mineName) {
        List<String> messages = theme.resetMessages();
        if (messages.isEmpty()) {
            return null;
        }
        String message = messages.get(ThreadLocalRandom.current().nextInt(messages.size()));
        // A component placeholder keeps the mine name's own colors inside it.
        return MiniMessage.miniMessage().deserialize(message, Placeholder.component("mine", mineName));
    }

    // ---------------------------------------------------------------- themes.yml

    /** (Re)loads themes.yml. The first time, writes the six default themes. */
    public void load() {
        themes.clear();
        if (!file.exists()) {
            for (Theme theme : DEFAULTS) {
                themes.put(theme.id(), theme);
            }
            save();
            plugin.getLogger().info("Created themes.yml with the six default themes.");
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("themes");
        if (section == null) {
            plugin.getLogger().warning("themes.yml has no 'themes' section, so there are no themes.");
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection t = section.getConfigurationSection(id);
            if (t == null) {
                continue;
            }
            // A built-in theme fills in what the file leaves out, so settings added in later versions get their
            // defaults on servers whose themes.yml is older.
            Theme base = defaultFor(id);
            List<String> ranks = new ArrayList<>();
            for (String rank : t.isList("ranks") ? t.getStringList("ranks") : base.ranks()) {
                ranks.add(rank.toLowerCase(Locale.ROOT));
            }
            themes.put(id, new Theme(id,
                t.getString("name", base.name()),
                t.getInt("level", base.level()),
                List.copyOf(ranks),
                t.getString("title", base.title()),
                t.getString("subtitle", base.subtitle()),
                t.isSet("sound") ? parseSound(id, t.getString("sound", "")) : base.sound(),
                t.isList("reset-messages") ? List.copyOf(t.getStringList("reset-messages")) : base.resetMessages()));
        }
    }

    /** Writes all themes to themes.yml. Only used for the defaults: admins edit the file and reload. */
    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
            "TheBox themes: each PvP band's name, title and reset messages. Edit freely, then run /thebox theme reload.",
            "name: what players see. Names, titles and messages use MiniMessage (https://docs.papermc.io/adventure/minimessage/format/).",
            "level: mines at this PvP level use the theme, unless /thebox mine settheme gives a mine another one.",
            "ranks: the band's ranks. Ranking up into one of them from a rank outside the band shows the title and plays the sound.",
            "sound: a Minecraft sound id, e.g. block.beacon.activate; leave it empty for none.",
            "reset-messages: one is picked at random when one of the theme's mines resets; <mine> is the mine's name.",
            "Settings added in later TheBox versions get their defaults for the six built-in themes if they're missing here."));
        for (Theme theme : themes.values()) {
            String path = "themes." + theme.id() + ".";
            yaml.set(path + "name", theme.name());
            yaml.set(path + "level", theme.level());
            yaml.set(path + "ranks", theme.ranks());
            yaml.set(path + "title", theme.title());
            yaml.set(path + "subtitle", theme.subtitle());
            yaml.set(path + "sound", theme.sound() == null ? "" : theme.sound().asString());
            yaml.set(path + "reset-messages", theme.resetMessages());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save themes.yml: " + e.getMessage());
        }
    }

    private Key parseSound(String themeId, String sound) {
        if (sound.isBlank()) {
            return null;
        }
        try {
            return Key.key(sound);
        } catch (InvalidKeyException e) {
            plugin.getLogger().warning("Theme '" + themeId + "': '" + sound + "' isn't a valid sound id, so its title plays no sound.");
            return null;
        }
    }

    // ---------------------------------------------------------------- the default themes

    /** The built-in theme with this id, or an empty one (no ranks, no level, no messages) for a theme an admin added. */
    private static Theme defaultFor(String id) {
        for (Theme theme : DEFAULTS) {
            if (theme.id().equals(id)) {
                return theme;
            }
        }
        return new Theme(id, id, -1, List.of(), id.toUpperCase(Locale.ROOT), "", null, List.of());
    }

    /**
     * The six default sites, one per PvP band. The prison ships players somewhere stranger with each band, so ranking
     * up into a new band reads as a transfer. Colors follow the rank colors (README section 6): titles in the band's
     * strong shade, messages in its palest.
     */
    private static final List<Theme> DEFAULTS = List.of(
        new Theme("yard", "<#C5C5C5>The Yard", 0, List.of("a", "b", "c"),
            "<#E8E8E8>THE YARD", "<gray>Break rocks. Earn your way out.", Key.key("block.iron_door.close"),
            List.of("<gray>A fresh load of rock is dumped into <mine>.",
                "<gray>The guards refill <mine>. Back to work.")),
        new Theme("sludgeworks", "<#5ADD66>The Sludgeworks", 1, List.of("d", "e", "f", "g", "h"),
            "<#5ADD66>THE SLUDGEWORKS", "<gray>Transferred. Mind the fumes.", Key.key("block.brewing_stand.brew"),
            List.of("<#A0EEA2>Fresh sludge pours into <mine>.",
                "<#A0EEA2>The vats overflow and refill <mine>.")),
        new Theme("cryo_vault", "<#48E1E9>The Cryo Vault", 2, List.of("i", "j", "k", "l", "m"),
            "<#48E1E9>THE CRYO VAULT", "<gray>Transferred. Something is thawing.", Key.key("entity.player.hurt_freeze"),
            List.of("<#83EFF5>A cold front refreezes <mine>.",
                "<#83EFF5>The cryo pumps hiss. <mine> is frozen solid again.")),
        new Theme("blackout", "<#78A7FF>The Blackout", 3, List.of("n", "o", "p", "q", "r"),
            "<#78A7FF>THE BLACKOUT", "<gray>Transferred. Bring a light.", Key.key("ambient.cave"),
            List.of("<#AAC8FF>Something rebuilt <mine> in the dark.",
                "<#AAC8FF>The tunnels of <mine> collapse and settle.")),
        new Theme("neon_undercity", "<#E381F9>The Neon Undercity", 4, List.of("s", "t", "u", "v", "w"),
            "<#E381F9>THE NEON UNDERCITY", "<gray>Transferred. Watch your back.", Key.key("block.respawn_anchor.charge"),
            List.of("<#EEB6FB>The night shift restocks <mine>.",
                "<#EEB6FB>Fresh contraband drops into <mine>.")),
        new Theme("starfall", "<#FE5C59>Starfall", 5, List.of("x", "y", "z"),
            "<#FE5C59>STARFALL", "<gray>Transferred. Mind the edge.", Key.key("block.end_portal.spawn"),
            List.of("<#FE9B93>A meteor shower refills <mine>.",
                "<#FE9B93>Debris from the belt slams into <mine>.")));
}
