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
package com.auto1.pantera.asto;

import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NotDirectoryException;
import java.util.Optional;

/**
 * Classifies a storage failure as a path clash in the storage tree: a file
 * stands where a directory is needed (at any depth of the key), or a
 * directory stands where the file should go.
 *
 * <p>A clash is a client error (the requested key cannot coexist with what
 * is stored), so upload slices answer it with {@code 409 Conflict} instead
 * of a server error. File-system storages report every clash with one of
 * the NIO exceptions recognised here, anywhere in the cause chain.</p>
 *
 * @since 2.2.9
 */
public final class PathClash {

    /**
     * Failure to classify.
     */
    private final Throwable err;

    /**
     * Ctor.
     * @param err Failure to classify
     */
    public PathClash(final Throwable err) {
        this.err = err;
    }

    /**
     * The clash exception in the failure's cause chain.
     * @return The clash exception, empty when the failure is not a clash
     */
    public Optional<Throwable> cause() {
        Throwable cur = this.err;
        int depth = 0;
        while (cur != null && depth < 32) {
            if (cur instanceof FileAlreadyExistsException
                || cur instanceof DirectoryNotEmptyException
                || cur instanceof NotDirectoryException) {
                return Optional.of(cur);
            }
            cur = cur.getCause();
            depth += 1;
        }
        return Optional.empty();
    }
}
