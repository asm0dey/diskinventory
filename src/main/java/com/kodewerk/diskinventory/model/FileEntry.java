package com.kodewerk.diskinventory.model;

/**
 * One file directly inside a directory. {@code size} is the logical (apparent)
 * size; {@code allocated} is the on-disk size.
 */
public record FileEntry(String name, long size, long allocated) {

    public long size(SizeMode mode) {
        return mode == SizeMode.ALLOCATED ? allocated : size;
    }

    @Override
    public String toString() {
        return name + "  " + Sizes.human(size);
    }
}
