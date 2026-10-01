package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledOnOs({OS.MAC, OS.LINUX})
class HardlinkTest {

    private static final int MIB = 10 * 1024 * 1024;

    private final DiskUsageModel model = new DiskUsageModel();

    private static void write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[bytes]);
    }

    private static void link(Path from, Path to) throws IOException {
        Files.createDirectories(from.getParent());
        Files.createLink(from, to);
    }

    private static DirectoryNode node(ScanResult r, String rel) {
        DirectoryNode n = r.root();
        for (Path segment : Path.of(rel)) {
            n = n.child(segment.toString()).orElseThrow();
        }
        return n;
    }

    private static FileEntry entry(ScanResult r, String rel) {
        Path p = Path.of(rel);
        DirectoryNode dir = p.getParent() == null ? r.root() : node(r, p.getParent().toString());
        String name = p.getFileName().toString();
        return dir.files().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void hardlinkCountedOnce(@TempDir Path root) throws IOException {
        write(root.resolve("a/f"), MIB);
        link(root.resolve("b/f"), root.resolve("a/f"));
        ScanResult r = model.scan(root);
        assertEquals(MIB, r.root().totalSize());
        assertEquals(MIB, node(r, "a").totalSize());
        assertEquals(0, node(r, "b").totalSize());
        assertEquals(0, node(r, "b").totalSize(SizeMode.ALLOCATED));
        assertEquals(node(r, "a").totalSize(SizeMode.ALLOCATED), r.root().totalSize(SizeMode.ALLOCATED));
        assertEquals(MIB, entry(r, "b/f").size());     // listed at full size, not charged
    }

    @Test
    void twoLinksInOneDirectoryCountedOnce(@TempDir Path root) throws IOException {
        write(root.resolve("d/x"), MIB);
        link(root.resolve("d/y"), root.resolve("d/x"));
        ScanResult r = model.scan(root);
        assertEquals(MIB, node(r, "d").totalSize());
        assertEquals(MIB, node(r, "d").directFileSize());
        assertEquals(MIB, r.root().totalSize());
    }

    @Test
    void scanParentResettlesOwnership(@TempDir Path parent) throws IOException {
        write(parent.resolve("child/f"), MIB);
        link(parent.resolve("aaa/f"), parent.resolve("child/f"));
        ScanResult known = model.scan(parent.resolve("child"));
        assertEquals(MIB, known.root().totalSize());
        ScanResult up = model.scanParent(known, (d, n, b) -> { });
        assertEquals(MIB, up.root().totalSize());
        assertEquals(MIB, node(up, "aaa").totalSize());
        assertEquals(0, node(up, "child").totalSize());
        assertEquals(MIB, known.root().totalSize());    // previous result untouched
    }

    @Test
    void scanParentWithGrownSharedFileMovesTheOldCharge(@TempDir Path parent) throws IOException {
        write(parent.resolve("child/f"), MIB);
        link(parent.resolve("aaa/f"), parent.resolve("child/f"));
        ScanResult known = model.scan(parent.resolve("child"));
        Files.write(parent.resolve("child/f"), new byte[2 * MIB], StandardOpenOption.APPEND);

        ScanResult up = model.scanParent(known, (d, n, b) -> { });

        assertEquals(0, node(up, "child").totalSize());
        assertEquals(0, node(up, "child").totalSize(SizeMode.ALLOCATED));
        assertEquals(3 * MIB, node(up, "aaa").totalSize());
        assertEquals(3 * MIB, up.root().totalSize());
    }
}
