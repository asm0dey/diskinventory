package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AllocatedSizeProbeTest {

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void nlinkReadByProbeMatchesJdk(@TempDir Path dir) throws IOException {
        Path a = Files.write(dir.resolve("a"), new byte[10]);
        Files.createLink(dir.resolve("b"), a);
        Files.createLink(dir.resolve("c"), a);
        Path single = Files.write(dir.resolve("single"), new byte[10]);
        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            assertNotNull(probe);
            for (Path p : List.of(a, single)) {
                assertEquals(((Number) Files.getAttribute(p, "unix:nlink", NOFOLLOW_LINKS)).longValue(),
                        probe.stat(p, 0).nlink());
            }
            assertEquals(3, probe.stat(a, 0).nlink());
            assertEquals(new AllocatedSizeProbe.Stat(77, 1), probe.stat(dir.resolve("missing"), 77));
        }
    }
}
