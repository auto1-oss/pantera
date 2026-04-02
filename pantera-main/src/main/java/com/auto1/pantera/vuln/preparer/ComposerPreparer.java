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
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@link ArtifactPreparer} for PHP Composer packages ({@code .zip}).
 *
 * <p>Composer packages are distributed as zip archives containing the package
 * source code. The preparer extracts {@code composer.json} and
 * {@code composer.lock} (if present) from the archive for scanning.
 *
 * <p>Both Trivy and Grype recognise these files and can identify CVEs in
 * declared PHP dependencies.
 *
 * @since 2.2.0
 */
public final class ComposerPreparer implements ArtifactPreparer {

    /**
     * PHP dependency manifest filenames recognised by Trivy and Grype.
     */
    private static final Set<String> MANIFESTS = Set.of(
        "composer.json", "composer.lock"
    );

    @Override
    public boolean supports(final String artifactPath) {
        final String lower = artifactPath.toLowerCase(Locale.US);
        // Composer archives are .zip files; exclude Go module zips (contain /@v/)
        return lower.endsWith(".zip") && !lower.contains("/@v/");
    }

    @Override
    public boolean prepare(final byte[] artifactBytes, final Path scanDir) throws IOException {
        boolean found = false;
        try (
            InputStream bais = new ByteArrayInputStream(artifactBytes);
            ZipInputStream zis = new ZipInputStream(bais)
        ) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                final String entryName = entry.getName();
                final String base = entryName.contains("/")
                    ? entryName.substring(entryName.lastIndexOf('/') + 1)
                    : entryName;
                if (!MANIFESTS.contains(base)) {
                    continue;
                }
                // Path-traversal guard: write flat into scanDir only
                final Path dest = scanDir.resolve(base).normalize();
                if (!dest.startsWith(scanDir)) {
                    continue;
                }
                Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                found = true;
            }
        }
        return found;
    }
}
