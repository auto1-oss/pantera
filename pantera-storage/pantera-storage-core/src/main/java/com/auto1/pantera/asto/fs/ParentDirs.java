/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.asto.fs;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;

/**
 * Creates the directory a stored file goes into, reporting a file standing
 * in the way as a path clash.
 *
 * <p>{@link Files#createDirectories} throws {@link FileAlreadyExistsException}
 * only when the immediate parent is a file ({@code a.txt/c.txt}). When the
 * file is further up ({@code a.txt/d/e.txt}) it fails with a plain
 * {@link java.nio.file.FileSystemException} ("Not a directory"), which does
 * not say what went wrong. This finds the file that blocks the path and
 * throws {@link NotDirectoryException} for it, so every depth of clash is
 * recognised by {@link com.auto1.pantera.asto.PathClash}.</p>
 *
 * @since 2.2.9
 */
public final class ParentDirs {

    /**
     * Directory to create.
     */
    private final Path dir;

    /**
     * Ctor.
     * @param dir Directory to create, with any missing ancestors
     */
    public ParentDirs(final Path dir) {
        this.dir = dir;
    }

    /**
     * Create the directory and its missing ancestors.
     * @throws IOException On failure; {@link NotDirectoryException} (with
     *  the original failure as its cause) when a file blocks the path
     */
    public void create() throws IOException {
        try {
            Files.createDirectories(this.dir);
        } catch (final IOException err) {
            final Path file = this.blockingFile();
            if (file == null) {
                throw err;
            }
            final NotDirectoryException clash = new NotDirectoryException(file.toString());
            clash.initCause(err);
            throw clash;
        }
    }

    /**
     * The nearest ancestor (or the directory itself) that is a regular file.
     * The walk stops at the first existing directory: nothing above it can
     * block the path.
     * @return The blocking file, or null if there is none
     */
    private Path blockingFile() {
        Path cur = this.dir;
        Path found = null;
        while (cur != null && found == null && !Files.isDirectory(cur)) {
            if (Files.isRegularFile(cur)) {
                found = cur;
            }
            cur = cur.getParent();
        }
        return found;
    }
}
