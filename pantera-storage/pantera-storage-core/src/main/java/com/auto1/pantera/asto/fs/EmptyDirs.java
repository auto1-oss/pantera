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
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Removes empty directories of a filesystem storage, never files, never
 * symbolic links and never the storage root itself.
 *
 * <p>A symlinked directory (e.g. a repository directory placed on another
 * volume) is always kept, whatever its target holds: {@code Files.delete}
 * unlinks a symlink without checking the target, so it is never passed one,
 * and an upward prune stops at the link.</p>
 *
 * <p>Blocking: callers run it off the event loop, on the storage's own
 * blocking executor.</p>
 *
 * @since 2.2.9
 */
public final class EmptyDirs {

    /**
     * Name of the shared temporary-upload directory at the storage root.
     */
    private static final String TMP = ".tmp";

    /**
     * Storage root.
     */
    private final Path root;

    /**
     * Ctor.
     * @param root Storage root directory
     */
    public EmptyDirs(final Path root) {
        this.root = root.normalize().toAbsolutePath();
    }

    /**
     * Delete every empty directory in the subtree of {@code start} (deepest
     * first, {@code start} included), then the empty directories above it up
     * to the root, then the root's {@code .tmp} directory when it is empty.
     * A directory that gains an entry concurrently is simply kept.
     * @param start Subtree root
     */
    public void pruneTree(final Path start) {
        final Path top = start.normalize().toAbsolutePath();
        if (!top.startsWith(this.root)) {
            throw new PanteraIOException(
                String.format("Entry path is out of storage: %s", start)
            );
        }
        if (EmptyDirs.isRealDirectory(top)) {
            final List<Path> dirs;
            try (Stream<Path> walk = Files.walk(top)) {
                dirs = walk.filter(EmptyDirs::isRealDirectory)
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .collect(Collectors.toList());
            } catch (final NoSuchFileException gone) {
                this.pruneUp(top.getParent());
                return;
            } catch (final IOException err) {
                throw new PanteraIOException(err);
            }
            for (final Path dir : dirs) {
                if (!dir.equals(this.root)) {
                    EmptyDirs.deleteIfEmpty(dir);
                }
            }
        }
        this.pruneUp(top);
    }

    /**
     * Delete {@code dir} and its ancestors while they are empty, stopping at
     * the root (exclusive); then the root's {@code .tmp} if it is empty.
     * @param dir Directory to start from
     */
    public void pruneUp(final Path dir) {
        Path cur = dir == null ? null : dir.normalize().toAbsolutePath();
        while (cur != null && cur.startsWith(this.root) && !cur.equals(this.root)
            && EmptyDirs.deleteIfEmpty(cur)) {
            cur = cur.getParent();
        }
        EmptyDirs.deleteIfEmpty(this.root.resolve(EmptyDirs.TMP));
    }

    /**
     * Delete a directory if it exists, is empty and is not a symlink.
     * @param dir Directory
     * @return True if the directory is gone (deleted now or already absent);
     *  false if it holds entries, is a symlink or is not a directory
     */
    private static boolean deleteIfEmpty(final Path dir) {
        boolean gone;
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            gone = true;
        } else if (EmptyDirs.isRealDirectory(dir)) {
            try {
                Files.delete(dir);
                gone = true;
            } catch (final DirectoryNotEmptyException busy) {
                gone = false;
            } catch (final NoSuchFileException raced) {
                gone = true;
            } catch (final IOException err) {
                throw new PanteraIOException(err);
            }
        } else {
            gone = false;
        }
        return gone;
    }

    /**
     * A directory that is not itself a symbolic link.
     * @param path Path
     * @return True for a real directory
     */
    private static boolean isRealDirectory(final Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }
}
