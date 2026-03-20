/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.layout;

import com.artipie.asto.Key;
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
