/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.layout;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Integration tests demonstrating how to use storage layouts with actual storage.
 */
class StorageLayoutIntegrationTest {

    @Test
    void testMavenArtifactStorage() throws Exception {
        final Storage storage = new InMemoryStorage();
        final StorageLayout layout = LayoutFactory.forType("maven");
        
        // Create artifact info
        final Map<String, String> meta = new HashMap<>();
        meta.put(MavenLayout.GROUP_ID, "com.example");
        meta.put(MavenLayout.ARTIFACT_ID, "my-app");
        
        final BaseArtifactInfo artifact = new BaseArtifactInfo(
            "maven-repo",
            "my-app",
            "1.0.0",
            meta
        );
        
        // Get storage path and save artifact
        final Key path = layout.artifactPath(artifact);
        final Key jarPath = new Key.From(path, "my-app-1.0.0.jar");
        
        final byte[] content = "jar content".getBytes(StandardCharsets.UTF_8);
        storage.save(jarPath, new Content.From(content)).join();
        
        // Verify artifact was stored correctly
        Assertions.assertTrue(storage.exists(jarPath).join());
        Assertions.assertEquals(
            "maven-repo/com/example/my-app/1.0.0/my-app-1.0.0.jar",
            jarPath.string()
        );
        
        // Verify content
        final byte[] retrieved = storage.value(jarPath)
            .thenCompose(Content::asBytesFuture)
            .join();
        Assertions.assertArrayEquals(content, retrieved);
    }

    @Test
    void testNpmScopedPackageStorage() throws Exception {
        final Storage storage = new InMemoryStorage();
        final StorageLayout layout = LayoutFactory.forType("npm");
        
        // Create scoped package info
        final Map<String, String> meta = new HashMap<>();
        meta.put(NpmLayout.SCOPE, "@angular");
        
        final BaseArtifactInfo artifact = new BaseArtifactInfo(
            "npm-repo",
            "core",
            "14.0.0",
            meta
        );
        
        // Get storage path and save artifact
        final Key path = layout.artifactPath(artifact);
        final Key tgzPath = new Key.From(path, "core-14.0.0.tgz");
        
        final byte[] content = "tgz content".getBytes(StandardCharsets.UTF_8);
        storage.save(tgzPath, new Content.From(content)).join();
        
        // Verify artifact was stored correctly
        Assertions.assertTrue(storage.exists(tgzPath).join());
        Assertions.assertEquals(
            "npm-repo/@angular/core/-/core-14.0.0.tgz",
            tgzPath.string()
        );
    }

    @Test
    void testComposerVendorPackageStorage() throws Exception {
        final Storage storage = new InMemoryStorage();
        final StorageLayout layout = LayoutFactory.forType("composer");
        
        final BaseArtifactInfo artifact = new BaseArtifactInfo(
            "composer-repo",
            "symfony/console",
            "5.4.0"
        );
        
        // Get storage path and save artifact
        final Key path = layout.artifactPath(artifact);
        final Key zipPath = new Key.From(path, "console-5.4.0.zip");
        
        final byte[] content = "zip content".getBytes(StandardCharsets.UTF_8);
        storage.save(zipPath, new Content.From(content)).join();
        
        // Verify artifact was stored correctly
        Assertions.assertTrue(storage.exists(zipPath).join());
        Assertions.assertEquals(
            "composer-repo/symfony/console/5.4.0/console-5.4.0.zip",
            zipPath.string()
        );
    }

    @Test
    void testFileLayoutWithNestedPath() throws Exception {
        final Storage storage = new InMemoryStorage();
        final StorageLayout layout = LayoutFactory.forType("file");
        
        final Map<String, String> meta = new HashMap<>();
        meta.put(FileLayout.UPLOAD_PATH, "/file-repo/releases/v1.0/app.jar");
        
        final BaseArtifactInfo artifact = new BaseArtifactInfo(
            "file-repo",
            "app.jar",
            "",
            meta
        );
        
        // Get storage path and save artifact
        final Key path = layout.artifactPath(artifact);
        final Key filePath = new Key.From(path, "app.jar");
        
        final byte[] content = "jar content".getBytes(StandardCharsets.UTF_8);
        storage.save(filePath, new Content.From(content)).join();
        
        // Verify artifact was stored correctly
        Assertions.assertTrue(storage.exists(filePath).join());
        Assertions.assertEquals(
            "file-repo/releases/v1.0/app.jar",
            filePath.string()
        );
    }

    @Test
    void testMultipleVersionsInSameRepository() throws Exception {
        final Storage storage = new InMemoryStorage();
        final StorageLayout layout = LayoutFactory.forType("pypi");
        
        // Store version 1.0.0
        final BaseArtifactInfo v1 = new BaseArtifactInfo(
            "pypi-repo",
            "requests",
            "1.0.0"
        );
        final Key path1 = layout.artifactPath(v1);
        final Key file1 = new Key.From(path1, "requests-1.0.0.whl");
        storage.save(file1, new Content.From("v1".getBytes())).join();
        
        // Store version 2.0.0
        final BaseArtifactInfo v2 = new BaseArtifactInfo(
            "pypi-repo",
            "requests",
            "2.0.0"
        );
        final Key path2 = layout.artifactPath(v2);
        final Key file2 = new Key.From(path2, "requests-2.0.0.whl");
        storage.save(file2, new Content.From("v2".getBytes())).join();
        
        // Verify both versions exist
        Assertions.assertTrue(storage.exists(file1).join());
        Assertions.assertTrue(storage.exists(file2).join());
        
        // Verify paths are different
        Assertions.assertNotEquals(path1.string(), path2.string());
        Assertions.assertEquals("pypi-repo/requests/1.0.0", path1.string());
        Assertions.assertEquals("pypi-repo/requests/2.0.0", path2.string());
    }
}
