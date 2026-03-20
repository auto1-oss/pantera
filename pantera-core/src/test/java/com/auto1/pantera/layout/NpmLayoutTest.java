/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link NpmLayout}.
 */
class NpmLayoutTest {

    @Test
    void testUnscopedArtifactPath() {
        final NpmLayout layout = new NpmLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "npm-repo",
            "express",
            "4.18.0"
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "npm-repo/express/-",
            path.string()
        );
    }

    @Test
    void testScopedArtifactPath() {
        final NpmLayout layout = new NpmLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(NpmLayout.SCOPE, "@angular");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "npm-repo",
            "core",
            "14.0.0",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "npm-repo/@angular/core/-",
            path.string()
        );
    }

    @Test
    void testScopedArtifactPathWithAtSign() {
        final NpmLayout layout = new NpmLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(NpmLayout.SCOPE, "babel");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "npm-internal",
            "preset-env",
            "7.20.0",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "npm-internal/@babel/preset-env/-",
            path.string()
        );
    }

    @Test
    void testUnscopedMetadataPath() {
        final NpmLayout layout = new NpmLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "npm-repo",
            "express",
            "4.18.0"
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "npm-repo/express",
            path.string()
        );
    }

    @Test
    void testScopedMetadataPath() {
        final NpmLayout layout = new NpmLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(NpmLayout.SCOPE, "@angular");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "npm-repo",
            "core",
            "14.0.0",
            meta
        );
        
        final Key path = layout.metadataPath(info);
        Assertions.assertEquals(
            "npm-repo/@angular/core",
            path.string()
        );
    }
}
