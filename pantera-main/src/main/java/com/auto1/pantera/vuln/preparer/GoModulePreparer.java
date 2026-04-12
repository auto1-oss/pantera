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
 * {@link ArtifactPreparer} for Go module artifacts.
 *
 * <p>Go module proxies store artifacts as:
 * <ul>
 *   <li>{@code @v/vX.Y.Z.mod} — the go.mod file (dependency manifest)</li>
 *   <li>{@code @v/vX.Y.Z.zip} — source archive containing go.mod and go.sum</li>
 * </ul>
 *
 * <p>For {@code .mod} files, the bytes are written directly as {@code go.mod}.
 * For {@code .zip} archives, the preparer extracts {@code go.mod} and
 * {@code go.sum} from inside the zip.
 *
 * @since 2.2.0
 */
public final class GoModulePreparer implements ArtifactPreparer {

    /**
     * Go dependency manifest filenames.
     */
    private static final Set<String> MANIFESTS = Set.of("go.mod", "go.sum");

    @Override
    public boolean supports(final String artifactPath) {
        final String lower = artifactPath.toLowerCase(Locale.US);
        return lower.endsWith(".mod") || (lower.endsWith(".zip") && lower.contains("/@v/"));
    }

    @Override
    public boolean prepare(final byte[] artifactBytes, final Path scanDir) throws IOException {
        final String name = "detect";
        // .mod files are the go.mod content directly
        if (artifactBytes.length > 0 && artifactBytes[0] != 'P') {
            // Not a ZIP (ZIP magic = PK). Treat as raw go.mod content.
            // go.mod files start with "module" keyword
            final String start = new String(
                artifactBytes, 0, Math.min(artifactBytes.length, 20),
                java.nio.charset.StandardCharsets.UTF_8
            );
            if (start.startsWith("module ") || start.startsWith("//")) {
                Files.write(scanDir.resolve("go.mod"), artifactBytes);
                return true;
            }
        }
        // ZIP archive: extract go.mod and go.sum
        return extractFromZip(artifactBytes, scanDir);
    }

    /**
     * Extract go.mod and go.sum from a Go module zip archive.
     * Go module zips have entries like {@code module@version/go.mod}.
     */
    private static boolean extractFromZip(
        final byte[] bytes, final Path destDir
    ) throws IOException {
        boolean found = false;
        try (
            InputStream bais = new ByteArrayInputStream(bytes);
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
                final Path dest = destDir.resolve(base).normalize();
                if (!dest.startsWith(destDir)) {
                    continue;
                }
                Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                found = true;
            }
        }
        return found;
    }
}
