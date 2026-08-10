package com.kodewerk.diskinventory.model;

/** Human-readable byte counts, 1024-based. */
public final class Sizes {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};

    private Sizes() {
    }

    public static String human(long bytes) {
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < UNITS.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return unit == 0
                ? bytes + " B"
                : String.format("%.1f %s", value, UNITS[unit]);
    }
}
