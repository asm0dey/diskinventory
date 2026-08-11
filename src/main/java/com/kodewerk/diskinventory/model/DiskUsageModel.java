package com.kodewerk.diskinventory.model;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Headless scan API. {@link #scan(Path)} walks the tree once, summing logical
 * file sizes ({@code Files.size} semantics), and returns an immutable
 * {@link DirectoryNode} tree. Symbolic links are not followed; unreadable
 * entries are counted, not fatal.
 */
public final class DiskUsageModel {

    /** Progress callback, invoked once per directory as the walk enters it. */
    @FunctionalInterface
    public interface ScanListener {
        void entering(Path directory, long directoriesSoFar, long bytesSoFar);
    }

    /** Thrown when the scanning thread is interrupted mid-walk. */
    public static final class ScanCancelledException extends IOException {
        ScanCancelledException() {
            super("scan cancelled");
        }
    }

    public DirectoryNode scan(Path root) throws IOException {
        return scan(root, (dir, dirs, bytes) -> { });
    }

    public DirectoryNode scan(Path root, ScanListener listener) throws IOException {
        return scan(root, listener, null);
    }

    /**
     * Scans the parent directory of an already-scanned node, grafting the known
     * subtree in place rather than re-walking it. Only the parent's other
     * children touch the disk. Returns {@code known} unchanged if it is already
     * the filesystem root.
     */
    public DirectoryNode scanParent(DirectoryNode known, ScanListener listener) throws IOException {
        Path parent = known.path().getParent();
        if (parent == null) {
            return known;
        }
        return scan(parent, listener, known);
    }

    /**
     * Whether allocated (on-disk) sizes are real on this platform and build.
     * Performs an actual lstat downcall rather than only constructing the
     * probe: an unregistered downcall stub in a native image surfaces either
     * when the handle is created or when the call is made, depending on the
     * build, and only a real call rules out both.
     */
    public static boolean allocatedSizeSupported() {
        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            return probe != null && probe.allocatedOf(Path.of("."), -1L) >= 0L;
        }
    }

    private DirectoryNode scan(Path root, ScanListener listener, DirectoryNode graft) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new IOException("Directory is not readable: " + root);
        }

        try (AllocatedSizeProbe probe = AllocatedSizeProbe.create()) {
            Visitor visitor = new Visitor(listener, graft, probe);
            Files.walkFileTree(root, visitor);
            return visitor.result();
        }
    }

    private static final class Building {
        final Path path;
        long directFileSize;
        long totalSize;
        long directAllocated;
        long totalAllocated;
        int errorCount;
        final List<DirectoryNode> children = new ArrayList<>();
        final List<FileEntry> files = new ArrayList<>();

        Building(Path path) {
            this.path = path;
        }
    }

    private static final class Visitor extends SimpleFileVisitor<Path> {
        private final ScanListener listener;
        private final DirectoryNode graft;
        private final AllocatedSizeProbe probe;
        private final Deque<Building> stack = new ArrayDeque<>();
        private long dirsSoFar;
        private long bytesSoFar;
        private long fileTally;
        private DirectoryNode result;

        Visitor(ScanListener listener, DirectoryNode graft, AllocatedSizeProbe probe) {
            this.listener = listener;
            this.graft = graft;
            this.probe = probe;
        }

        DirectoryNode result() {
            return result;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            checkCancelled();
            if (graft != null && dir.equals(graft.path())) {
                Building parent = stack.peek();
                parent.children.add(graft);
                parent.totalSize += graft.totalSize();
                parent.totalAllocated += graft.totalSize(SizeMode.ALLOCATED);
                parent.errorCount += graft.errorCount();
                bytesSoFar += graft.totalSize();
                return FileVisitResult.SKIP_SUBTREE;
            }
            dirsSoFar++;
            listener.entering(dir, dirsSoFar, bytesSoFar);
            stack.push(new Building(dir));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            // Symlinks are neither followed nor counted; only real files sum.
            if (attrs.isSymbolicLink()) {
                return FileVisitResult.CONTINUE;
            }
            if ((++fileTally & 0xFFF) == 0) {
                checkCancelled();
            }
            Building current = stack.peek();
            long size = attrs.size();
            long allocated = probe != null ? probe.allocatedOf(file, size) : size;
            current.directFileSize += size;
            current.totalSize += size;
            current.directAllocated += allocated;
            current.totalAllocated += allocated;
            current.files.add(new FileEntry(file.getFileName().toString(), size, allocated));
            bytesSoFar += size;
            return FileVisitResult.CONTINUE;
        }

        private static void checkCancelled() throws ScanCancelledException {
            if (Thread.currentThread().isInterrupted()) {
                throw new ScanCancelledException();
            }
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // An unreadable subdirectory arrives here instead of preVisitDirectory.
            stack.peek().errorCount++;
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            Building finished = stack.pop();
            if (exc != null) {
                finished.errorCount++;
            }
            finished.children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());
            finished.files.sort(Comparator.comparingLong((FileEntry f) -> f.size()).reversed());
            DirectoryNode node = new DirectoryNode(finished.path, finished.directFileSize,
                    finished.totalSize, finished.directAllocated, finished.totalAllocated,
                    finished.children, finished.files, finished.errorCount);

            Building parent = stack.peek();
            if (parent == null) {
                result = node;
            } else {
                parent.totalSize += node.totalSize();
                parent.totalAllocated += node.totalSize(SizeMode.ALLOCATED);
                parent.errorCount += node.errorCount();
                parent.children.add(node);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
