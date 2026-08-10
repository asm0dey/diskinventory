package com.kodewerk.diskinventory.model;

/** Which size a report is based on. */
public enum SizeMode {
    /** Apparent size: {@code Files.size()}, like {@code du --apparent-size}. */
    LOGICAL,
    /** On-disk size: {@code st_blocks * 512}, like {@code du}. Sparse files show small. */
    ALLOCATED
}
