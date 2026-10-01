package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One directory in a completed scan. Immutable; the whole tree is built in a
 * single pass so drill-down never touches the disk again. Both logical
 * (apparent) and allocated (on-disk) sizes are tracked; no-argument accessors
 * report logical sizes.
 */
public final class DirectoryNode {

    private final Path path;
    private final long directFileSize;
    private final long totalSize;
    private final long directAllocated;
    private final long totalAllocated;
    private final List<DirectoryNode> children;
    private final List<FileEntry> files;
    private final int errorCount;

    DirectoryNode(Path path, long directFileSize, long totalSize,
                  long directAllocated, long totalAllocated,
                  List<DirectoryNode> children, List<FileEntry> files, int errorCount) {
        this.path = path;
        this.directFileSize = directFileSize;
        this.totalSize = totalSize;
        this.directAllocated = directAllocated;
        this.totalAllocated = totalAllocated;
        this.children = List.copyOf(children);
        this.files = List.copyOf(files);
        this.errorCount = errorCount;
    }

    public Path path() {
        return path;
    }

    public String name() {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }

    /** Logical bytes in files directly in this directory (excluding subdirectories). */
    public long directFileSize() {
        return directFileSize;
    }

    /** Logical recursive total: directFileSize plus the totalSize of every child. */
    public long totalSize() {
        return totalSize;
    }

    public long directFileSize(SizeMode mode) {
        return mode == SizeMode.ALLOCATED ? directAllocated : directFileSize;
    }

    public long totalSize(SizeMode mode) {
        return mode == SizeMode.ALLOCATED ? totalAllocated : totalSize;
    }

    /** Child directories, sorted by logical totalSize descending. */
    public List<DirectoryNode> children() {
        return children;
    }

    /** Files directly in this directory, sorted by logical size descending. */
    public List<FileEntry> files() {
        return files;
    }

    /** Entries under this node (recursive) that could not be read. */
    public int errorCount() {
        return errorCount;
    }

    public Optional<DirectoryNode> child(String name) {
        return children.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /**
     * The {@code limit} largest files anywhere under this node (recursive),
     * biggest first in the given mode. A shared file appears once, under its
     * smallest path within this node (its other links elsewhere in the subtree
     * are dropped); {@code otherLinks} is left empty here since a {@link
     * DirectoryNode} has no index to resolve them — see {@link
     * ScanResult#largestFiles}.
     */
    public List<FileRef> largestFiles(int limit, SizeMode mode) {
        if (limit <= 0) {
            return List.of();
        }
        List<FileRef> unshared = new ArrayList<>();
        Map<Object, FileRef> sharedBest = new HashMap<>();
        Deque<DirectoryNode> pending = new ArrayDeque<>();
        pending.push(this);
        while (!pending.isEmpty()) {
            DirectoryNode node = pending.pop();
            for (FileEntry file : node.files) {
                FileRef ref = new FileRef(node.path, file, List.of());
                if (file.shared()) {
                    sharedBest.merge(file.fileKey(), ref,
                            (a, b) -> a.path().compareTo(b.path()) <= 0 ? a : b);
                } else {
                    unshared.add(ref);
                }
            }
            node.children.forEach(pending::push);
        }
        List<FileRef> all = new ArrayList<>(unshared);
        all.addAll(sharedBest.values());
        all.sort(Comparator.comparingLong((FileRef r) -> r.file().size(mode)).reversed());
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    @Override
    public String toString() {
        return name() + " (" + Sizes.human(totalSize) + ")";
    }
}
