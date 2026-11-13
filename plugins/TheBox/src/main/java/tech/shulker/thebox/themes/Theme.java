// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.themes;

import java.util.List;
import net.kyori.adventure.key.Key;

/**
 * A band's look and feel, from themes.yml.
 *
 * @param id            the key in themes.yml, used by /thebox mine settheme
 * @param name          what players see (MiniMessage)
 * @param level         mines at this PvP level use the theme, unless they're given another one
 * @param ranks         the band's ranks; ranking up into one of them from outside the band shows the title
 * @param title         the band title (MiniMessage)
 * @param subtitle      the line under it (MiniMessage)
 * @param sound         played with the title, or null for none
 * @param resetMessages one is picked at random when one of the theme's mines resets; {@code <mine>} is the mine's name
 */
public record Theme(String id, String name, int level, List<String> ranks, String title, String subtitle, Key sound,
                    List<String> resetMessages) {
}
