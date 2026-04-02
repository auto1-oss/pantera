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
package com.auto1.pantera.vuln;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Extracts dependency manifest file(s) from a downloaded artifact so the
 * scanner backend can find them in a flat directory.
 *
 * <p>Each implementation handles one artifact format (npm tgz, Maven pom,
 * PyPI sdist, Go module, PHP Composer, etc.). Only the minimal files required
 * by the scanner are extracted — full artifact contents are never written to disk.
 *
 * <p>Implementations should be stateless and reusable across scans.
 *
 * @since 2.2.0
 */
public interface ArtifactPreparer {

    /**
     * Returns {@code true} if this preparer handles the given artifact path.
     * Matching is typically done on the file extension or path pattern.
     *
     * @param artifactPath Storage path of the artifact,
     *                     e.g. {@code lodash/-/lodash-4.17.21.tgz}
     * @return True if this preparer supports the artifact format
     */
    boolean supports(String artifactPath);

    /**
     * Extract dependency manifest file(s) from the artifact bytes into
     * {@code scanDir}.
     *
     * <p>On success, writes one or more manifest files into the flat
     * {@code scanDir} directory. Returns {@code false} if no recognisable
     * manifest was found (e.g. an npm tarball shipped without a lock file),
     * in which case the caller skips the scan and records an empty report.
     *
     * @param artifactBytes Raw artifact bytes (already read from storage)
     * @param scanDir Empty temporary directory to write manifest file(s) into
     * @return {@code true} if at least one manifest file was written;
     *         {@code false} to skip the scan
     * @throws IOException On I/O failure during extraction
     */
    boolean prepare(byte[] artifactBytes, Path scanDir) throws IOException;
}
