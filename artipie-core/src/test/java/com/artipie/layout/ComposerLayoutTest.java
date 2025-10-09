/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import com.artipie.asto.Key;
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
