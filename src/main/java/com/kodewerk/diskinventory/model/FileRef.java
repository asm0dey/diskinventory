package com.kodewerk.diskinventory.model;

import java.nio.file.Path;

/** A file located within a scanned tree: its directory plus its entry. */
public record FileRef(Path directory, FileEntry file) {

    public Path path() {
        return directory.resolve(file.name());
    }
}
