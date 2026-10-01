package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds only the path from an edited node up to the tree root, reusing
 * every untouched subtree by reference. The two primitives here back both
 * rescan (swap a subtree for a freshly-scanned one) and hardlink-ownership
 * moves (charge a size delta into one directory and its ancestors).
 */
final class TreeEdit {

    private TreeEdit() {
    }

    /** A logical/allocated byte delta to apply to a directory's direct size. */
    record Charge(long size, long allocated) {
        Charge plus(Charge o) {
            return new Charge(size + o.size, allocated + o.allocated);
        }

        Charge negate() {
            return new Charge(-size, -allocated);
        }
    }

    /**
     * Removes any child directory and any file entry named {@code p.getFileName()}
     * from {@code p}'s parent, then inserts {@code dir} or {@code file} (at most
     * one non-null). Only the path from the parent up to {@code root} is rebuilt;
     * every other subtree is reused by reference.
     */
    static DirectoryNode replace(DirectoryNode root, Path p, DirectoryNode dir, FileEntry file) {
        if (dir != null && file != null) {
            throw new IllegalArgumentException("at most one of dir/file may be non-null");
        }
        if (!p.startsWith(root.path()) || p.equals(root.path())) {
            throw new IllegalArgumentException("path is not strictly under root: " + p);
        }
        Path rel = root.path().relativize(p);
        List<String> names = new ArrayList<>(rel.getNameCount());
        for (Path segment : rel) {
            names.add(segment.toString());
        }
        return replaceAt(root, names, 0, dir, file);
    }

    private static DirectoryNode replaceAt(DirectoryNode node, List<String> names, int idx,
                                            DirectoryNode dir, FileEntry file) {
        String name = names.get(idx);
        if (idx == names.size() - 1) {
            return replaceEntry(node, name, dir, file);
        }
        DirectoryNode child = node.child(name)
                .orElseThrow(() -> new IllegalArgumentException("directory not in tree: " + name));
        DirectoryNode newChild = replaceAt(child, names, idx + 1, dir, file);
        return replaceChild(node, child, newChild);
    }

    private static DirectoryNode replaceEntry(DirectoryNode parent, String name,
                                               DirectoryNode newDir, FileEntry newFile) {
        List<DirectoryNode> children = new ArrayList<>(parent.children());
        DirectoryNode removedDir = null;
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).name().equals(name)) {
                removedDir = children.remove(i);
                break;
            }
        }
        if (newDir != null) {
            children.add(newDir);
        }
        children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());

        List<FileEntry> files = new ArrayList<>(parent.files());
        files.removeIf(f -> f.name().equals(name));
        if (newFile != null) {
            files.add(newFile);
        }
        files.sort(Comparator.comparingLong((FileEntry f) -> f.size()).reversed());

        // Shared files are charged separately via charge(); they never count
        // toward their directory's own direct size.
        long directFileSize = files.stream().mapToLong(f -> f.shared() ? 0 : f.size()).sum();
        long directAllocated = files.stream().mapToLong(f -> f.shared() ? 0 : f.allocated()).sum();
        long childTotal = children.stream().mapToLong(DirectoryNode::totalSize).sum();
        long childAllocated = children.stream().mapToLong(c -> c.totalSize(SizeMode.ALLOCATED)).sum();

        int removedErrors = removedDir != null ? removedDir.errorCount() : 0;
        int addedErrors = newDir != null ? newDir.errorCount() : 0;
        int errorCount = parent.errorCount() - removedErrors + addedErrors;

        return new DirectoryNode(parent.path(), directFileSize, directFileSize + childTotal,
                directAllocated, directAllocated + childAllocated, children, files, errorCount);
    }

    private static DirectoryNode replaceChild(DirectoryNode parent, DirectoryNode oldChild, DirectoryNode newChild) {
        List<DirectoryNode> children = new ArrayList<>(parent.children());
        children.set(children.indexOf(oldChild), newChild);
        children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());

        long totalSize = parent.totalSize() - oldChild.totalSize() + newChild.totalSize();
        long totalAllocated = parent.totalSize(SizeMode.ALLOCATED) - oldChild.totalSize(SizeMode.ALLOCATED)
                + newChild.totalSize(SizeMode.ALLOCATED);
        int errorCount = parent.errorCount() - oldChild.errorCount() + newChild.errorCount();

        return new DirectoryNode(parent.path(), parent.directFileSize(), totalSize,
                parent.directFileSize(SizeMode.ALLOCATED), totalAllocated,
                children, parent.files(), errorCount);
    }

    /**
     * Adds each delta to its directory's direct size and to every ancestor's
     * total. Keys that don't name a directory in the tree are ignored. Each
     * affected directory is rebuilt once; everything else is reused by reference.
     */
    static DirectoryNode charge(DirectoryNode root, Map<Path, Charge> deltas) {
        return chargeNode(root, deltas);
    }

    private static DirectoryNode chargeNode(DirectoryNode node, Map<Path, Charge> deltas) {
        boolean changed = deltas.containsKey(node.path());
        List<DirectoryNode> children = new ArrayList<>(node.children());
        long totalSize = node.totalSize();
        long totalAllocated = node.totalSize(SizeMode.ALLOCATED);
        for (int i = 0; i < children.size(); i++) {
            DirectoryNode child = children.get(i);
            if (deltas.keySet().stream().anyMatch(k -> k.startsWith(child.path()))) {
                DirectoryNode newChild = chargeNode(child, deltas);
                if (newChild != child) {
                    totalSize += newChild.totalSize() - child.totalSize();
                    totalAllocated += newChild.totalSize(SizeMode.ALLOCATED) - child.totalSize(SizeMode.ALLOCATED);
                    children.set(i, newChild);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return node;
        }

        long directFileSize = node.directFileSize();
        long directAllocated = node.directFileSize(SizeMode.ALLOCATED);
        Charge direct = deltas.get(node.path());
        if (direct != null) {
            directFileSize += direct.size();
            directAllocated += direct.allocated();
            totalSize += direct.size();
            totalAllocated += direct.allocated();
        }

        children.sort(Comparator.comparingLong((DirectoryNode n) -> n.totalSize()).reversed());
        return new DirectoryNode(node.path(), directFileSize, totalSize, directAllocated, totalAllocated,
                children, node.files(), node.errorCount());
    }
}
