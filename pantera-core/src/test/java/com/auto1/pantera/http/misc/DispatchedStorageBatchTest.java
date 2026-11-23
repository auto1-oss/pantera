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
package com.auto1.pantera.http.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ListPage;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Paged listing, batch delete and upload abort through
 * {@link DispatchedStorage}: forwarded to the delegate's own implementation
 * and completed on the pool of their kind.
 *
 * <p>The delegate answers only when the test releases it, after the test
 * has attached its callback: the callback then runs on the thread that
 * completes the dispatched future, never on the test thread.</p>
 *
 * @since 2.2.14
 */
final class DispatchedStorageBatchTest {

    @Test
    @Timeout(30)
    void listPageIsForwardedAndCompletesOnListPool() throws Exception {
        final Recorder delegate = new Recorder();
        delegate.save(
            new Key.From("repo/a"), new Content.From("a".getBytes(StandardCharsets.UTF_8))
        ).join();
        final CompletableFuture<String> thread = new DispatchedStorage(delegate)
            .listPage(new Key.From("repo"), Optional.of("repo/0"), 7)
            .thenApply(page -> Thread.currentThread().getName());
        delegate.release();
        MatcherAssert.assertThat(
            "the completion runs on the list pool",
            thread.get(10, TimeUnit.SECONDS), new StringStartsWith("pantera-io-list-")
        );
        MatcherAssert.assertThat(
            "the delegate's own listPage was called with the same arguments",
            delegate.calls, new IsEqual<>(List.of("listPage repo repo/0 7"))
        );
    }

    @Test
    @Timeout(30)
    void deleteBatchIsForwardedAndCompletesOnWritePool() throws Exception {
        final Recorder delegate = new Recorder();
        final CompletableFuture<String> thread = new DispatchedStorage(delegate)
            .deleteBatch(List.of(new Key.From("repo/a"), new Key.From("repo/b")))
            .thenApply(nothing -> Thread.currentThread().getName());
        delegate.release();
        MatcherAssert.assertThat(
            "the completion runs on the write pool",
            thread.get(10, TimeUnit.SECONDS), new StringStartsWith("pantera-io-write-")
        );
        MatcherAssert.assertThat(
            "the delegate's own deleteBatch was called once, not delete per key",
            delegate.calls, new IsEqual<>(List.of("deleteBatch 2"))
        );
    }

    @Test
    @Timeout(30)
    void abortIncompleteUploadsIsForwardedAndCompletesOnWritePool() throws Exception {
        final Recorder delegate = new Recorder();
        final CompletableFuture<Integer> count = new DispatchedStorage(delegate)
            .abortIncompleteUploads(new Key.From("repo"));
        final CompletableFuture<String> thread = count
            .thenApply(nothing -> Thread.currentThread().getName());
        delegate.release();
        MatcherAssert.assertThat(
            "the delegate's count is returned",
            count.get(10, TimeUnit.SECONDS), new IsEqual<>(42)
        );
        MatcherAssert.assertThat(
            "the completion runs on the write pool",
            thread.get(10, TimeUnit.SECONDS), new StringStartsWith("pantera-io-write-")
        );
    }

    /**
     * Storage that records the calls of the batch operations.
     * @since 2.2.14
     */
    private static final class Recorder extends Storage.Wrap {

        /**
         * Calls.
         */
        private final List<String> calls;

        /**
         * Completed when the batch operations may answer.
         */
        private final CompletableFuture<Void> gate;

        /**
         * Ctor.
         */
        Recorder() {
            super(new InMemoryStorage());
            this.calls = new java.util.concurrent.CopyOnWriteArrayList<>();
            this.gate = new CompletableFuture<>();
        }

        /**
         * Let the batch operations answer.
         */
        void release() {
            this.gate.complete(null);
        }

        @Override
        public CompletableFuture<ListPage> listPage(
            final Key prefix, final Optional<String> after, final int limit
        ) {
            this.calls.add(
                String.format("listPage %s %s %d", prefix.string(), after.orElse("-"), limit)
            );
            return this.gate.thenCompose(open -> super.listPage(prefix, after, limit));
        }

        @Override
        public CompletableFuture<Void> deleteBatch(final Collection<Key> keys) {
            this.calls.add(String.format("deleteBatch %d", keys.size()));
            return this.gate.thenCompose(open -> super.deleteBatch(keys));
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            this.calls.add(String.format("delete %s", key.string()));
            return super.delete(key);
        }

        @Override
        public CompletableFuture<Integer> abortIncompleteUploads(final Key prefix) {
            return this.gate.thenApply(open -> 42);
        }
    }
}
