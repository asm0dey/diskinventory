package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeEditTest {

    private static void write(Path file, int bytes) throws IOException {
        Files.write(file, new byte[bytes]);
    }

    private static DirectoryNode scan(Path root) throws IOException {
        return new DiskUsageModel().scan(root);
    }

    @Test
    void treeEditSharesUntouchedSubtrees(@TempDir Path root) throws IOException {
        Path a = Files.createDirectory(root.resolve("a"));
        Path x = Files.createDirectory(a.resolve("x"));
        write(x.resolve("f"), 100);
        Path b = Files.createDirectory(root.resolve("b"));
        write(b.resolve("g"), 200);

        DirectoryNode before = scan(root);
        DirectoryNode after = TreeEdit.replace(before, root.resolve("a/x/f"), null, null);

        assertSame(before.child("b").orElseThrow(), after.child("b").orElseThrow());
        assertEquals(200, after.totalSize());
        assertEquals(0, after.child("a").orElseThrow().totalSize());
    }

    @Test
    void replaceInsertsNewDirectoryAndKeepsSortOrder(@TempDir Path root) throws IOException {
        Path small = Files.createDirectory(root.resolve("small"));
        write(small.resolve("f"), 10);
        Path big = Files.createDirectory(root.resolve("big"));
        write(big.resolve("f"), 500);

        DirectoryNode before = scan(root);

        Path newDir = Files.createDirectory(root.resolve("new"));
        write(newDir.resolve("f"), 1000);
        DirectoryNode scannedNew = scan(newDir);

        DirectoryNode after = TreeEdit.replace(before, root.resolve("new"), scannedNew, null);

        assertEquals(List.of("new", "big", "small"),
                after.children().stream().map(DirectoryNode::name).toList());
        assertEquals(1510, after.totalSize());
    }

    @Test
    void replaceSwapsFileForDirectoryOfSameName(@TempDir Path root, @TempDir Path other) throws IOException {
        write(root.resolve("n"), 100);
        DirectoryNode before = scan(root);

        Path n = Files.createDirectory(other.resolve("n"));
        write(n.resolve("a"), 100);
        write(n.resolve("b"), 200);
        DirectoryNode scannedN = scan(n);

        DirectoryNode after = TreeEdit.replace(before, root.resolve("n"), scannedN, null);

        assertTrue(after.files().stream().noneMatch(f -> f.name().equals("n")));
        assertEquals(300, after.child("n").orElseThrow().totalSize());
        assertEquals(300, after.totalSize());
    }

    @Test
    void chargeAddsToDirectAndAncestorTotals(@TempDir Path root) throws IOException {
        Files.createDirectory(root.resolve("a"));
        Path b = Files.createDirectory(root.resolve("a").resolve("b"));
        DirectoryNode before = scan(root);

        DirectoryNode charged = TreeEdit.charge(before, Map.of(b, new TreeEdit.Charge(10, 4096)));

        DirectoryNode chargedB = charged.child("a").orElseThrow().child("b").orElseThrow();
        assertEquals(10, chargedB.directFileSize());
        assertEquals(4096, chargedB.directFileSize(SizeMode.ALLOCATED));
        assertEquals(10, charged.child("a").orElseThrow().totalSize());
        assertEquals(10, charged.totalSize());

        DirectoryNode reverted = TreeEdit.charge(charged, Map.of(b, new TreeEdit.Charge(10, 4096).negate()));
        assertEquals(0, reverted.totalSize());
        assertEquals(0, reverted.child("a").orElseThrow().child("b").orElseThrow().directFileSize());

        DirectoryNode ignored = TreeEdit.charge(before, Map.of(root.resolve("zzz"), new TreeEdit.Charge(1, 1)));
        assertSame(before, ignored);
    }

    @Test
    void replaceRejectsPathsNotUnderRoot(@TempDir Path root) throws IOException {
        DirectoryNode tree = scan(root);

        assertThrows(IllegalArgumentException.class, () -> TreeEdit.replace(tree, root, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> TreeEdit.replace(tree, root.resolve("missing/x"), null, null));
    }
}
