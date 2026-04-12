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
package com.auto1.pantera.vuln.preparer;

import com.auto1.pantera.vuln.ArtifactPreparer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * {@link ArtifactPreparer} for npm tarballs ({@code .tgz}).
 *
 * <p>Streams through the tarball (without writing a temp artifact file) and
 * extracts the first matching dependency manifest — {@code package-lock.json},
 * {@code yarn.lock}, or {@code npm-shrinkwrap.json} — into the scan directory.
 * That file is the only input Trivy (or any equivalent scanner) needs.
 *
 * @since 2.2.0
 */
public final class NpmArtifactPreparer implements ArtifactPreparer {

    /**
     * npm manifest filenames recognised by Trivy and Grype.
     * Published npm packages contain only package.json (not lock files);
     * lock files may exist when scanning a full project tarball.
     */
    private static final Set<String> MANIFESTS = Set.of(
        "package-lock.json", "yarn.lock", "npm-shrinkwrap.json", "package.json"
    );

    @Override
    public boolean supports(final String artifactPath) {
        return artifactPath.toLowerCase(Locale.US).endsWith(".tgz");
    }

    @Override
    public boolean prepare(final byte[] artifactBytes, final Path scanDir) throws IOException {
        return extractFirstMatchingEntry(artifactBytes, scanDir, MANIFESTS).isPresent();
    }

    /**
     * Stream through a gzipped tarball and copy the first entry whose base filename
     * matches one of the given {@code names} into {@code destDir}.
     *
     * <p>The artifact bytes are read via a {@link ByteArrayInputStream} — no
     * temporary artifact file is written to disk.
     *
     * @param bytes Artifact bytes
     * @param destDir Flat destination directory for the extracted manifest
     * @param names Set of base filenames to look for
     * @return Base filename of the first match found, or empty
     * @throws IOException On I/O failure
     */
    static Optional<String> extractFirstMatchingEntry(
        final byte[] bytes, final Path destDir, final Set<String> names
    ) throws IOException {
        try (
            InputStream bais = new ByteArrayInputStream(bytes);
            GzipCompressorInputStream gzis = new GzipCompressorInputStream(bais);
            TarArchiveInputStream tis = new TarArchiveInputStream(gzis)
        ) {
            TarArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                if (entry.isDirectory() || !tis.canReadEntryData(entry)) {
                    continue;
                }
                final String entryName = entry.getName();
                final String base = entryName.contains("/")
                    ? entryName.substring(entryName.lastIndexOf('/') + 1)
                    : entryName;
                if (!names.contains(base)) {
                    continue;
                }
                // Path-traversal guard: write flat into destDir only
                final Path dest = destDir.resolve(base).normalize();
                if (!dest.startsWith(destDir)) {
                    continue;
                }
                Files.copy(tis, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return Optional.of(base);
            }
        }
        return Optional.empty();
    }
}
