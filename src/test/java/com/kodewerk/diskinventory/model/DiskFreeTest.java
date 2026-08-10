package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskFreeTest {

    @Test
    void reportsFilesystemForPath(@TempDir Path dir) {
        String output = DiskFree.df(dir);

        assertFalse(output.isBlank());
        // Header line plus at least one filesystem line.
        assertTrue(output.lines().count() >= 2, "unexpected df output: " + output);
    }

    @Test
    void parsesMacStyleOutputMergingMountedOn() {
        String raw = """
                Filesystem      Size   Used  Avail Capacity iused ifree %iused  Mounted on
                /dev/disk3s5   1.8Ti  1.5Ti  273Gi    85%    1052004 2860872840    0%   /System/Volumes/Data
                """;

        DiskFree.Table table = DiskFree.parse(raw);

        assertEquals(9, table.headers().size());
        assertEquals("Mounted on", table.headers().getLast());
        assertEquals(1, table.rows().size());
        assertEquals("/dev/disk3s5", table.rows().getFirst().getFirst());
        assertEquals("/System/Volumes/Data", table.rows().getFirst().getLast());
    }

    @Test
    void parsesLinuxStyleOutput() {
        String raw = """
                Filesystem      Size  Used Avail Use% Mounted on
                /dev/nvme0n1p2  916G  388G  482G  45% /
                """;

        DiskFree.Table table = DiskFree.parse(raw);

        assertEquals(List.of("Filesystem", "Size", "Used", "Avail", "Use%", "Mounted on"),
                table.headers());
        assertEquals(List.of("/dev/nvme0n1p2", "916G", "388G", "482G", "45%", "/"),
                table.rows().getFirst());
    }

    @Test
    void failureOutputParsesToEmptyTable() {
        assertTrue(DiskFree.parse("df: timed out").isEmpty());
        assertTrue(DiskFree.parse("").isEmpty());
    }
}
