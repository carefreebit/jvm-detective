package io.github.carefreebit.jvmd.util;

import java.util.Locale;

/** Human-friendly number formatting shared by every output format. */
public final class Format {

    private Format() {
    }

    /** 0..1 ratio to a whole percentage, e.g. 0.734 -> "73%". */
    public static String pct(double ratio) {
        if (Double.isNaN(ratio) || ratio <= 0) {
            return "0%";
        }
        return Math.min(100, Math.round(ratio * 100)) + "%";
    }

    /** Bytes to a compact binary size, e.g. 268435456 -> "256 MB". */
    public static String mb(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        double mib = bytes / (1024.0 * 1024.0);
        if (mib >= 1024) {
            return String.format(Locale.ROOT, "%.1f GB", mib / 1024);
        }
        if (mib >= 10) {
            return Math.round(mib) + " MB";
        }
        return String.format(Locale.ROOT, "%.1f MB", mib);
    }

    /** Duration in days/hours/minutes, e.g. "2d 04h" or "41m". */
    public static String uptime(long millis) {
        if (millis <= 0) {
            return "?";
        }
        long totalMinutes = millis / 60_000;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0) {
            return days + "d " + String.format(Locale.ROOT, "%02dh", hours);
        }
        if (hours > 0) {
            return hours + "h " + String.format(Locale.ROOT, "%02dm", minutes);
        }
        return minutes + "m";
    }
}
