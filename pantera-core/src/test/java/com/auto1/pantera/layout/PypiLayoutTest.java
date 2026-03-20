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
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

/**
 * Tests for {@link PypiLayout}.
 */
class PypiLayoutTest {

    @Test
    void testArtifactPath() {
        final PypiLayout layout = new PypiLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "pypi-repo",
            "requests",
            "2.28.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "pypi-repo/requests/2.28.0",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithHyphens() {
        final PypiLayout layout = new PypiLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "pypi-internal",
            "my-package-name",
            "1.0.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "pypi-internal/my-package-name/1.0.0",
            path.string()
        );
    }

    @Test
    void testMetadataPath() {
        final PypiLayout layout = new PypiLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "pypi-repo",
            "requests",
            "2.28.0"
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "pypi-repo/requests/2.28.0",
            path.string()
        );
    }
}
