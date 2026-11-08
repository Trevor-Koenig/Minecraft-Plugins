// SPDX-License-Identifier: AGPL-3.0-or-later
package tech.shulker.thebox.utils;

/** Formats lengths of time for players: "2d 4h", "3h 12m", "45m", "30s". */
public class Durations {

    public static String format(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86400;
        long hours = seconds % 86400 / 3600;
        long minutes = seconds % 3600 / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return seconds + "s";
    }
}
