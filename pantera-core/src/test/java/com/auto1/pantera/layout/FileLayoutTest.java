/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link FileLayout}.
 */
class FileLayoutTest {

    @Test
    void testArtifactPathWithNestedStructure() {
        final FileLayout layout = new FileLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(FileLayout.UPLOAD_PATH, "/file_repo/test/v3.2/file.gz");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "file_repo",
            "file.gz",
            "",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "file_repo/test/v3.2",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithoutLeadingSlash() {
        final FileLayout layout = new FileLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(FileLayout.UPLOAD_PATH, "file_repo/docs/manual.pdf");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "file_repo",
            "manual.pdf",
            "",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "file_repo/docs",
            path.string()
        );
    }

    @Test
    void testArtifactPathWithRepoNameInPath() {
        final FileLayout layout = new FileLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(FileLayout.UPLOAD_PATH, "/my-files/releases/v1.0/app.jar");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "my-files",
            "app.jar",
            "",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "my-files/releases/v1.0",
            path.string()
        );
    }

    @Test
    void testArtifactPathAtRoot() {
        final FileLayout layout = new FileLayout();
        final Map<String, String> meta = new HashMap<>();
        meta.put(FileLayout.UPLOAD_PATH, "/file_repo/readme.txt");
        
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "file_repo",
            "readme.txt",
            "",
            meta
        );
        
        final Key path = layout.artifactPath(info);
        Assertions.assertEquals(
            "file_repo",
            path.string()
        );
    }

    @Test
    void testMissingUploadPathThrowsException() {
        final FileLayout layout = new FileLayout();
        final BaseArtifactInfo info = new BaseArtifactInfo(
            "file_repo",
            "file.txt",
            ""
        );
        
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> layout.artifactPath(info)
        );
    }
}
