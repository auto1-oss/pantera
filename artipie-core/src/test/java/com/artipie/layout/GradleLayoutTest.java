/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import com.artipie.asto.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link GradleLayout}.
 */
class GradleLayoutTest {

    @Test
    void testArtifactPathWithSimpleGroupId() {
        final GradleLayout layout = new GradleLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(GradleLayout.GROUP_ID, "com.example");
        meta.put(GradleLayout.ARTIFACT_ID, "my-library");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "gradle-repo",
            "my-library",
            "1.0.0",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "gradle-repo/com/example/my-library/1.0.0",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithComplexGroupId() {
        final GradleLayout layout = new GradleLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(GradleLayout.GROUP_ID, "org.springframework.boot");
        meta.put(GradleLayout.ARTIFACT_ID, "spring-boot-starter");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "gradle-central",
            "spring-boot-starter",
            "2.7.0",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "gradle-central/org/springframework/boot/spring-boot-starter/2.7.0",
            path.string()
        );
    }

    @Test
    void testMetadataPath() {
        final GradleLayout layout = new GradleLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(GradleLayout.GROUP_ID, "com.example");
        meta.put(GradleLayout.ARTIFACT_ID, "my-library");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "gradle-repo",
            "my-library",
            "1.0.0",
            meta
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "gradle-repo/com/example/my-library/1.0.0",
            path.string()
        );
    }

    @Test
    void testMissingGroupIdThrowsException() {
        final GradleLayout layout = new GradleLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(GradleLayout.ARTIFACT_ID, "my-library");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "gradle-repo",
            "my-library",
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
        final GradleLayout layout = new GradleLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(GradleLayout.GROUP_ID, "com.example");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "gradle-repo",
            "my-library",
            "1.0.0",
            meta
        );
        
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> layout.artifactPath(info)
        );
    }
}
