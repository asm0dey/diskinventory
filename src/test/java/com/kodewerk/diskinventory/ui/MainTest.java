package com.kodewerk.diskinventory.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void scanOfARealDirectoryExitsZero(@TempDir Path root) throws IOException {
        Files.write(root.resolve("a.bin"), new byte[4096]);

        assertEquals(0, Main.scan(root));
    }

    @Test
    void scanOfAMissingPathExitsOne(@TempDir Path root) {
        assertEquals(1, Main.scan(root.resolve("does-not-exist")));
    }

    @Test
    void scanPrintsRootLogicalAllocatedAndErrors(@TempDir Path root) throws IOException {
        Files.write(root.resolve("a.bin"), new byte[4096]);

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            Main.scan(root);
        } finally {
            System.setOut(original);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("root"), output);
        assertTrue(output.contains("logical"), output);
        assertTrue(output.contains("allocated"), output);
        assertTrue(output.contains("errors"), output);
    }
}
