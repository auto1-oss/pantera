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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@link ArtifactPreparer} for Maven POM files ({@code .pom}).
 *
 * <p>Writes the POM bytes directly to {@code pom.xml} in the scan directory.
 * Trivy recognises the file by name ({@code pom.xml}), not by the
 * {@code .pom} extension used in Maven repositories.
 *
 * <p>No tarball extraction needed — the POM itself is the manifest.
 *
 * @since 2.2.0
 */
public final class MavenPomArtifactPreparer implements ArtifactPreparer {

    @Override
    public boolean supports(final String artifactPath) {
        return artifactPath.toLowerCase(Locale.US).endsWith(".pom");
    }

    @Override
    public boolean prepare(final byte[] artifactBytes, final Path scanDir) throws IOException {
        Files.write(scanDir.resolve("pom.xml"), artifactBytes);
        return true;
    }
}
