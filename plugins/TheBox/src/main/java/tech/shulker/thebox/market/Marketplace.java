// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.market;

import java.util.Map;
import java.util.TreeMap;
import tech.shulker.thebox.utils.SavedLocation;

/** A market area with rentable stalls. A server can have several. */
public class Marketplace {

    private final String id;
    /** Name players see, in MiniMessage format, or null to show the id. */
    private String displayName = null;
    /** Where /market sends players, or null if it hasn't been set. */
    private SavedLocation teleport = null;
    private final Map<String, Stall> stalls = new TreeMap<>();

    public Marketplace(String id) {
        this.id = id;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName != null ? displayName : id; }
    public boolean hasDisplayName() { return displayName != null; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public SavedLocation getTeleport() { return teleport; }
    public void setTeleport(SavedLocation teleport) { this.teleport = teleport; }
    public Map<String, Stall> getStalls() { return stalls; }
}
