package com.kodewerk.diskinventory.ui;

import com.kodewerk.diskinventory.model.DirectoryNode;
import com.kodewerk.diskinventory.model.DiskUsageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiskInventoryAppTest {

    @Test
    void trailToStopsAtTheNearestExistingAncestor(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/b"));
        DirectoryNode tree = new DiskUsageModel().scan(root).root();

        assertEquals(List.of(root, root.resolve("a"), root.resolve("a/b")),
                paths(DiskInventoryApp.trailTo(tree, root.resolve("a/b"))));
        assertEquals(List.of(root, root.resolve("a")),
                paths(DiskInventoryApp.trailTo(tree, root.resolve("a/gone/deeper"))));
        assertEquals(List.of(root), paths(DiskInventoryApp.trailTo(tree, root)));
        assertEquals(List.of(root), paths(DiskInventoryApp.trailTo(tree, root.getParent())));
    }

    private static List<Path> paths(List<DirectoryNode> nodes) {
        return nodes.stream().map(DirectoryNode::path).toList();
    }
}
