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
 * Tests for {@link HelmLayout}.
 */
class HelmLayoutTest {

    @Test
    void testArtifactPath() {
        final HelmLayout layout = new HelmLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "helm-repo",
            "nginx",
            "1.0.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "helm-repo/nginx",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithComplexName() {
        final HelmLayout layout = new HelmLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "helm-charts",
            "my-application-chart",
            "2.5.3"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "helm-charts/my-application-chart",
            path.string()
        );
    }

    @Test
    void testMetadataPath() {
        final HelmLayout layout = new HelmLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "helm-repo",
            "nginx",
            "1.0.0"
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "helm-repo/index.yaml",
            path.string()
        );
    }

    @Test
    void testMetadataPathIsRepositoryLevel() {
        final HelmLayout layout = new HelmLayout();
        final BaseArtifactInfo info1 = new BaseArtifactInfo(
            "helm-repo",
            "nginx",
            "1.0.0"
        );
        final BaseArtifactInfo info2 = new BaseArtifactInfo(
            "helm-repo",
            "redis",
            "2.0.0"
        );
        
        // Both should point to the same index.yaml
        Assertions.assertEquals(
            layout.metadataPath(info1).string(),
            layout.metadataPath(info2).string()
        );
    }
}
