/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
