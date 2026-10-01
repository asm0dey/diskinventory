package com.kodewerk.diskinventory.model;

/** A scanned tree plus the hardlink index its shared-file charges came from. */
public record ScanResult(DirectoryNode root, InodeIndex inodes) {
}
