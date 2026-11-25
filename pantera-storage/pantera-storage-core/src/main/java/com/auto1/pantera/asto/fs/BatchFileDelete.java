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

import com.auto1.pantera.asto.PanteraIOException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes a batch of files of a filesystem storage and then removes the
 * directories the batch left empty.
 *
 * <p>A file that is not there is not an error. Directories are never
 * deleted as if they were files. The empty-directory cleanup runs once per
 * distinct parent directory of the batch, after all files are gone, and
 * climbs towards the storage root (never above it) only while directories
 * are empty.</p>
 *
 * <p>Blocking: callers run it off the event loop.</p>
 *
 * @since 2.2.14
 */
public final class BatchFileDelete {

    /**
     * Failed files named in the error message at most.
     */
    private static final int NAMED = 5;

    /**
     * Storage root.
     */
    private final Path root;

    /**
     * Ctor.
     * @param root Storage root directory
     */
    public BatchFileDelete(final Path root) {
        this.root = root;
    }

    /**
     * Delete the files; every file is attempted even if one fails.
     * @param files Paths of the files, inside the storage root
     * @throws PanteraIOException Naming the number of files that could not
     *  be deleted and the first few of them
     */
    public void delete(final Collection<Path> files) {
        final Set<Path> parents = new LinkedHashSet<>();
        final List<String> failed = new ArrayList<>(BatchFileDelete.NAMED);
        int failures = 0;
        IOException first = null;
        for (final Path file : files) {
            try {
                BatchFileDelete.remove(file);
                // The parent of a file that was already gone is pruned too:
                // a repeated batch finishes the cleanup an interrupted one
                // left undone.
                if (file.getParent() != null) {
                    parents.add(file.getParent());
                }
            } catch (final IOException err) {
                failures += 1;
                if (first == null) {
                    first = err;
                }
                if (failed.size() < BatchFileDelete.NAMED) {
                    failed.add(String.format("%s (%s)", file, err.getClass().getSimpleName()));
                }
            }
        }
        final EmptyDirs dirs = new EmptyDirs(this.root);
        for (final Path parent : parents) {
            dirs.pruneUp(parent);
        }
        if (failures > 0) {
            throw new PanteraIOException(
                String.format(
                    "Failed to delete %d of %d files: %s",
                    failures, files.size(), String.join(", ", failed)
                ),
                first
            );
        }
    }

    /**
     * Delete one file if it is there.
     * @param file File path
     * @throws IOException If the file exists and cannot be deleted
     */
    private static void remove(final Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(file);
        }
    }
}
