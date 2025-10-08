/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.asto;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.gradle.Gradle;
import java.util.concurrent.CompletableFuture;

/**
 * Gradle repository implementation using ASTO storage.
 *
 * @since 1.0
 */
public final class AstoGradle implements Gradle {

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage
     */
    public AstoGradle(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Content> artifact(final Key key) {
        return this.storage.value(key)
            .thenApply(pub -> new Content.From(pub));
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        return this.storage.save(key, content);
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return this.storage.exists(key);
    }
}
