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

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link MavenLayout}.
 */
class MavenLayoutTest {

    @Test
    void testArtifactPathWithSimpleGroupId() {
        final MavenLayout layout = new MavenLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(MavenLayout.GROUP_ID, "com.example");
        meta.put(MavenLayout.ARTIFACT_ID, "my-artifact");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "maven-repo",
            "my-artifact",
            "1.0.0",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "maven-repo/com/example/my-artifact/1.0.0",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithComplexGroupId() {
        final MavenLayout layout = new MavenLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(MavenLayout.GROUP_ID, "org.apache.commons");
        meta.put(MavenLayout.ARTIFACT_ID, "commons-lang3");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "maven-central",
            "commons-lang3",
            "3.12.0",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "maven-central/org/apache/commons/commons-lang3/3.12.0",
            path.string()
        );
    }

    @Test
    void testMetadataPath() {
        final MavenLayout layout = new MavenLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(MavenLayout.GROUP_ID, "com.example");
        meta.put(MavenLayout.ARTIFACT_ID, "my-artifact");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "maven-repo",
            "my-artifact",
            "1.0.0",
            meta
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "maven-repo/com/example/my-artifact/maven-metadata.xml",
            path.string()
        );
    }

    @Test
    void testMissingGroupIdThrowsException() {
        final MavenLayout layout = new MavenLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(MavenLayout.ARTIFACT_ID, "my-artifact");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "maven-repo",
            "my-artifact",
            "1.0.0",
            meta
        );
        
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> layout.artifactPath(info)
        );
    }

    @Test
    void testMissingArtifactIdThrowsException() {
        final MavenLayout layout = new MavenLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(MavenLayout.GROUP_ID, "com.example");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "maven-repo",
            "my-artifact",
            "1.0.0",
            meta
        );
        
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> layout.artifactPath(info)
        );
    }
}
