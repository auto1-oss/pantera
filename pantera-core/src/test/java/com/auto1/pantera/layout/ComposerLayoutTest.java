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
 * Tests for {@link ComposerLayout}.
 */
class ComposerLayoutTest {

    @Test
    void testArtifactPathWithVendorAndPackage() {
        final ComposerLayout layout = new ComposerLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "composer-repo",
            "symfony/console",
            "5.4.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "composer-repo/symfony/console/5.4.0",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithSimpleName() {
        final ComposerLayout layout = new ComposerLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "composer-repo",
            "monolog",
            "2.3.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "composer-repo/monolog/2.3.0",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithMultipleSlashes() {
        final ComposerLayout layout = new ComposerLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "composer-internal",
            "vendor/package",
            "1.0.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "composer-internal/vendor/package/1.0.0",
            path.string()
        );
    }

    @Test
    void testMetadataPathWithVendorAndPackage() {
        final ComposerLayout layout = new ComposerLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "composer-repo",
            "symfony/console",
            "5.4.0"
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "composer-repo/symfony/console",
            path.string()
        );
    }

    @Test
    void testMetadataPathWithSimpleName() {
        final ComposerLayout layout = new ComposerLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "composer-repo",
            "monolog",
            "2.3.0"
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "composer-repo/monolog",
            path.string()
        );
    }
}
