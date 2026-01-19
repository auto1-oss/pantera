/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import java.util.concurrent.CompletableFuture;

/**
 * Gradle repository.
 *
 * @since 1.0
 */
public interface Gradle {

    /**
     * Get artifact by key.
     *
     * @param key Artifact key
     * @return Content if exists
     */
    CompletableFuture<Content> artifact(Key key);

    /**
     * Save artifact.
     *
     * @param key Artifact key
     * @param content Artifact content
     * @return Completion stage
     */
    CompletableFuture<Void> save(Key key, Content content);

    /**
     * Check if artifact exists.
     *
     * @param key Artifact key
     * @return True if exists
     */
    CompletableFuture<Boolean> exists(Key key);
}
