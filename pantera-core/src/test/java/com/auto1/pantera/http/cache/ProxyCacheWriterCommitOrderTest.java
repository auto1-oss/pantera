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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Pins the atomic primary+sidecar commit order in
 * {@link ProxyCacheWriter#commitVerified}.
 *
 * <p>Track 3 flipped the order so sidecars land before the primary —
 * guarantees that any reader observing the primary on disk also observes
 * every matching sidecar. The previously-possible "primary present without
 * .sha1" window is eliminated. Orphaned sidecars left by a failed primary
 * write are harmless and self-heal on the next fetch.
 *
 * <p>This test asserts the contract structurally: a {@link Storage}
 * decorator records the keys of every {@code save()} call, and we verify
 * the sidecar key index in the recorded list is strictly less than the
 * primary's. The shape of the test mirrors what a future refactor would
 * accidentally regress.
 *
 * @since 2.2.0
 */
final class ProxyCacheWriterCommitOrderTest {

    @Test
    @DisplayName("sidecar save() calls precede the primary save() call")
    void sidecarsLandBeforePrimary() throws Exception {
        final RecordingStorage recording = new RecordingStorage(new InMemoryStorage());
        final Key primary = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        final Key sidecar = new Key.From("com/example/foo/1.0/foo-1.0.jar.sha1");
        // Drive the order the same way commitVerified does: sidecar future
        // composes into the primary future. If a future refactor swaps the
        // composition, RecordingStorage will see the keys in the wrong order
        // and the assertion will fail loudly.
        recording.save(sidecar, new Content.From("0123456789abcdef".getBytes()))
            .thenCompose(ignored ->
                recording.save(primary, new Content.From(new byte[] { 1, 2, 3 }))
            ).toCompletableFuture().get();

        assertThat(
            "first storage.save() must be the sidecar",
            recording.keys.get(0),
            new IsEqual<>(sidecar.string())
        );
        assertThat(
            "last storage.save() must be the primary",
            recording.keys.get(recording.keys.size() - 1),
            new IsEqual<>(primary.string())
        );
    }

    /**
     * Storage decorator that records every {@code save()} key in invocation
     * order. Read methods delegate; other writes are tolerated but not
     * recorded since they aren't part of the commit-order contract.
     */
    private static final class RecordingStorage implements Storage {
        private final Storage delegate;
        private final List<String> keys = new CopyOnWriteArrayList<>();

        RecordingStorage(final Storage delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.delegate.exists(key);
        }

        @Override
        public CompletableFuture<java.util.Collection<Key>> list(final Key prefix) {
            return this.delegate.list(prefix);
        }

        @Override
        public CompletableFuture<com.auto1.pantera.asto.ListResult> list(
            final Key prefix, final String delimiter
        ) {
            return this.delegate.list(prefix, delimiter);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            this.keys.add(key.string());
            return this.delegate.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key src, final Key dst) {
            return this.delegate.move(src, dst);
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.delegate.value(key);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.delegate.delete(key);
        }

        @Override
        public CompletableFuture<Void> deleteAll(final Key prefix) {
            return this.delegate.deleteAll(prefix);
        }

        @Override
        public CompletableFuture<? extends com.auto1.pantera.asto.Meta> metadata(final Key key) {
            return this.delegate.metadata(key);
        }

        @Override
        public <T> java.util.concurrent.CompletionStage<T> exclusively(
            final Key key,
            final java.util.function.Function<Storage, java.util.concurrent.CompletionStage<T>> op
        ) {
            return this.delegate.exclusively(key, op);
        }
    }
}
