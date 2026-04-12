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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * {@link ArtifactPreparer} for PyPI source distributions ({@code .tar.gz}).
 *
 * <p>Streams through the sdist tarball and extracts the first recognised
 * Python dependency manifest ({@code requirements.txt}, {@code pyproject.toml},
 * etc.) into the scan directory.
 *
 * @since 2.2.0
 */
public final class PypiSdistArtifactPreparer implements ArtifactPreparer {

    /**
     * Python dependency manifest filenames recognised by Trivy and equivalent scanners.
     */
    private static final Set<String> MANIFESTS = Set.of(
        "requirements.txt", "requirements-dev.txt", "requirements-prod.txt",
        "setup.cfg", "pyproject.toml", "Pipfile.lock", "poetry.lock"
    );

    @Override
    public boolean supports(final String artifactPath) {
        return artifactPath.toLowerCase(Locale.US).endsWith(".tar.gz");
    }

    @Override
    public boolean prepare(final byte[] artifactBytes, final Path scanDir) throws IOException {
        return NpmArtifactPreparer.extractFirstMatchingEntry(
            artifactBytes, scanDir, MANIFESTS
        ).isPresent();
    }
}
