// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.ranks;

import java.util.Locale;

/** One rank on the ladder. Its id is also the name of its LuckPerms group. */
public class Rank {

    private final String id;

    /** Name players see, in MiniMessage format, or null to show the id in capitals ("A"). */
    private String displayName = null;

    /** What it costs to rank up to this rank. */
    private double cost = 0;

    public Rank(String id) {
        this.id = id;
    }

    public String getId() { return id; }

    /** The display name if one is set, otherwise the id in capitals. MiniMessage format. */
    public String getDisplayName() { return displayName != null ? displayName : id.toUpperCase(Locale.ROOT); }
    public boolean hasDisplayName() { return displayName != null; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
}
