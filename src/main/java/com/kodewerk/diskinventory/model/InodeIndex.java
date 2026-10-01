package com.kodewerk.diskinventory.model;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every shared file ({@code nlink > 1}) in a tree, keyed by fileKey: its links
 * in the tree and the one link that owns it. The owner is the smallest link by
 * {@link Path#compareTo}, so totals never depend on walk order; its bytes are
 * charged to the owner's parent directory. Mutable, so every operation works
 * on a {@link #copy()}.
 */
public final class InodeIndex {

    private static final class Inode {
        final TreeSet<Path> links;
        Path owner;
        long nlink;
        long size;
        long allocated;

        Inode(TreeSet<Path> links) {
            this.links = links;
        }

        TreeEdit.Charge charge() {
            return new TreeEdit.Charge(size, allocated);
        }
    }

    private final Map<Object, Inode> inodes = new HashMap<>();

    public InodeIndex() {
    }

    InodeIndex copy() {
        InodeIndex copy = new InodeIndex();
        inodes.forEach((key, inode) -> {
            Inode c = new Inode(new TreeSet<>(inode.links));
            c.owner = inode.owner;
            c.nlink = inode.nlink;
            c.size = inode.size;
            c.allocated = inode.allocated;
            copy.inodes.put(key, c);
        });
        return copy;
    }

    void addLink(Object key, Path link, long nlink, long size, long allocated) {
        Inode inode = inodes.computeIfAbsent(key, k -> new Inode(new TreeSet<>()));
        inode.links.add(link);
        inode.nlink = nlink;
        inode.size = size;
        inode.allocated = allocated;
    }

    /**
     * Drops every link equal to or under {@code p} and returns the keys that
     * lost one. An owner strictly under {@code p} is forgotten, since its charge
     * goes away with the replaced subtree; an owner equal to {@code p} is kept,
     * because its charge sits in {@code p}'s parent and {@link #settle} must undo it.
     */
    Set<Object> removeLinksUnder(Path p) {
        Set<Object> keys = new HashSet<>();
        inodes.forEach((key, inode) -> {
            if (inode.links.removeIf(l -> l.startsWith(p))) {
                keys.add(key);
            }
            if (inode.owner != null && inode.owner.startsWith(p) && !inode.owner.equals(p)) {
                inode.owner = null;
            }
        });
        return keys;
    }

    /**
     * Re-picks the owner of each given inode and returns the per-directory
     * deltas that move its charge: minus on the old owner's parent, plus on the
     * new one's. An inode left with no links is removed.
     */
    Map<Path, TreeEdit.Charge> settle(Set<Object> keys) {
        Map<Path, TreeEdit.Charge> deltas = new HashMap<>();
        for (Object key : keys) {
            Inode inode = inodes.get(key);
            if (inode == null) {
                continue;
            }
            Path owner = inode.links.isEmpty() ? null : inode.links.first();
            if (!Objects.equals(owner, inode.owner)) {
                if (inode.owner != null) {
                    deltas.merge(inode.owner.getParent(), inode.charge().negate(), TreeEdit.Charge::plus);
                }
                if (owner != null) {
                    deltas.merge(owner.getParent(), inode.charge(), TreeEdit.Charge::plus);
                }
                inode.owner = owner;
            }
            if (owner == null) {
                inodes.remove(key);
            }
        }
        return deltas;
    }

    public Optional<Path> owner(Object key) {
        Inode inode = inodes.get(key);
        return inode == null ? Optional.empty() : Optional.ofNullable(inode.owner);
    }

    public List<Path> linksOf(Object key) {
        Inode inode = inodes.get(key);
        return inode == null ? List.of() : List.copyOf(inode.links);
    }
}
