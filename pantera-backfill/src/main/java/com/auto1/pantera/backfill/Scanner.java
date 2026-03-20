/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Scans a repository root directory and produces a lazy stream of
 * {@link ArtifactRecord} instances. Implementations must ensure the
 * returned stream is lazy so that arbitrarily large repositories can
 * be processed with constant memory.
 *
 * @since 1.20.13
 */
@FunctionalInterface
public interface Scanner {

    /**
     * Scan the given repository root and produce artifact records.
     *
     * @param root Path to the repository root directory on disk
     * @param repoName Logical repository name
     * @return Lazy stream of artifact records
     * @throws IOException If an I/O error occurs while scanning
     */
    Stream<ArtifactRecord> scan(Path root, String repoName) throws IOException;
}
