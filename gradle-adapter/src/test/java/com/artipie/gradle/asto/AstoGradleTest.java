/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.asto;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for {@link AstoGradle}.
 *
 * @since 1.0
 */
class AstoGradleTest {

    @Test
    void savesAndRetrievesArtifact() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AstoGradle gradle = new AstoGradle(storage);
        final Key key = new Key.From("com/example/artifact/1.0/artifact-1.0.jar");
        final byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        
        gradle.save(key, new Content.From(data)).get();
        
        assertTrue(gradle.exists(key).get());
        final Content content = gradle.artifact(key).get();
        assertNotNull(content);
    }

    @Test
    void checksExistence() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AstoGradle gradle = new AstoGradle(storage);
        final Key key = new Key.From("com/example/artifact/1.0/artifact-1.0.jar");
        
        assertFalse(gradle.exists(key).get());
        
        gradle.save(key, new Content.From("data".getBytes(StandardCharsets.UTF_8))).get();
        
        assertTrue(gradle.exists(key).get());
    }

    @Test
    void retrievesNonExistentArtifact() {
        final Storage storage = new InMemoryStorage();
        final AstoGradle gradle = new AstoGradle(storage);
        final Key key = new Key.From("non/existent/artifact/1.0/artifact-1.0.jar");
        
        final CompletableFuture<Content> future = gradle.artifact(key);
        
        assertThrows(Exception.class, future::get);
    }
}
