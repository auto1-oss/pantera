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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link BlobStore#list(Key, String)} default implementation -- the
 * fallback used by backends without native delimiter support, mirroring
 * {@code com.auto1.pantera.asto.Storage}'s equivalent default (WS1.0, spec
 * &sect;I: {@code BlobStore} must behave the same regardless of backend).
 */
final class BlobStoreListDefaultTest {

    @Test
    void splitsFlatListingIntoFilesAndDirectoriesOneLevelBelowPrefix() {
        final BlobStore store = new FlatFakeBlobStore(
            Arrays.asList(
                "com/README.md",
                "com/google/guava/1.0/guava-1.0.jar",
                "com/google/guava/2.0/guava-2.0.jar",
                "com/apache/commons/1.0/commons-1.0.jar",
                "com/example/lib/1.0/lib-1.0.jar"
            )
        );
        final ListResult result = store.list(new Key.From("com"), "/").join();
        MatcherAssert.assertThat(
            "one file directly under com/",
            result.files().stream().map(Key::string).toList(),
            new IsEqual<>(List.of("com/README.md"))
        );
        // Key.From normalizes away a trailing delimiter (same as
        // com.auto1.pantera.asto.Storage's equivalent default), so directories
        // come back without it.
        MatcherAssert.assertThat(
            "three subdirectories directly under com/",
            result.directories().stream().map(Key::string).sorted().toList(),
            new IsEqual<>(List.of("com/apache", "com/example", "com/google"))
        );
    }

    @Test
    void emptyPrefixSplitsTopLevelEntries() {
        final BlobStore store = new FlatFakeBlobStore(
            Arrays.asList("root.txt", "dir/child.txt")
        );
        final ListResult result = store.list(Key.ROOT, "/").join();
        MatcherAssert.assertThat(result.files().size(), new IsEqual<>(1));
        MatcherAssert.assertThat(result.directories().size(), new IsEqual<>(1));
    }

    @Test
    void identifierDefaultsToSimpleClassName() {
        final BlobStore store = new FlatFakeBlobStore(List.of());
        MatcherAssert.assertThat(store.identifier(), new StringContains("FlatFakeBlobStore"));
    }

    /**
     * Minimal {@link BlobStore} exercising only {@link #list(Key)} -- exactly what
     * the default {@link BlobStore#list(Key, String)} method needs to be tested in
     * isolation from any real backend.
     */
    private static final class FlatFakeBlobStore implements BlobStore {

        /**
         * Flat recursive key listing this fake serves.
         */
        private final List<Key> keys;

        FlatFakeBlobStore(final List<String> keys) {
            this.keys = keys.stream().<Key>map(Key.From::new).toList();
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletableFuture<? extends Meta> head(final Key key) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletableFuture<Content> get(final Key key) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletableFuture<Void> put(final Key key, final Content content) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return CompletableFuture.completedFuture(this.keys);
        }
    }
}
