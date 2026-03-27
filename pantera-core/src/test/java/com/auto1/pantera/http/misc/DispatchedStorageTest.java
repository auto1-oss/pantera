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
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for {@link DispatchedStorage}.
 * <p>
 * Uses {@link SlowStorage} wrapper to ensure delegate futures complete
 * asynchronously, guaranteeing that downstream callbacks observe the
 * dispatched executor thread rather than the calling thread.
 *
 * @since 1.20.13
 */
final class DispatchedStorageTest {

    /**
     * Delegate in-memory storage.
     */
    private InMemoryStorage memory;

    /**
     * Storage under test (dispatched wrapper around async delegate).
     */
    private DispatchedStorage storage;

    @BeforeEach
    void setUp() {
        this.memory = new InMemoryStorage();
        this.storage = new DispatchedStorage(new SlowStorage(this.memory));
    }

    @Test
    void readOpsRunOnReadPool() throws Exception {
        final Key key = new Key.From("test-read");
        this.memory.save(
            key,
            new Content.From("data".getBytes(StandardCharsets.UTF_8))
        ).join();
        final AtomicReference<String> threadName = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        this.storage.exists(key)
            .whenComplete(
                (val, err) -> {
                    threadName.set(Thread.currentThread().getName());
                    latch.countDown();
                }
            );
        assertThat(
            "Latch should count down within timeout",
            latch.await(5, TimeUnit.SECONDS), is(true)
        );
        assertThat(
            "exists() completion should run on the read pool",
            threadName.get(), startsWith("pantera-io-read-")
        );
    }

    @Test
    void writeOpsRunOnWritePool() throws Exception {
        final Key key = new Key.From("test-write");
        final AtomicReference<String> threadName = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        this.storage.save(
            key,
            new Content.From("data".getBytes(StandardCharsets.UTF_8))
        ).whenComplete(
            (val, err) -> {
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            }
        );
        assertThat(
            "Latch should count down within timeout",
            latch.await(5, TimeUnit.SECONDS), is(true)
        );
        assertThat(
            "save() completion should run on the write pool",
            threadName.get(), startsWith("pantera-io-write-")
        );
    }

    @Test
    void listOpsRunOnListPool() throws Exception {
        final AtomicReference<String> threadName = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        this.storage.list(Key.ROOT)
            .whenComplete(
                (val, err) -> {
                    threadName.set(Thread.currentThread().getName());
                    latch.countDown();
                }
            );
        assertThat(
            "Latch should count down within timeout",
            latch.await(5, TimeUnit.SECONDS), is(true)
        );
        assertThat(
            "list() completion should run on the list pool",
            threadName.get(), startsWith("pantera-io-list-")
        );
    }

    @Test
    void deleteAllRunsOnWritePool() throws Exception {
        final Key key = new Key.From("test-delete-all", "item");
        this.memory.save(
            key,
            new Content.From("data".getBytes(StandardCharsets.UTF_8))
        ).join();
        final AtomicReference<String> threadName = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        this.storage.deleteAll(new Key.From("test-delete-all"))
            .whenComplete(
                (val, err) -> {
                    threadName.set(Thread.currentThread().getName());
                    latch.countDown();
                }
            );
        assertThat(
            "Latch should count down within timeout",
            latch.await(5, TimeUnit.SECONDS), is(true)
        );
        assertThat(
            "deleteAll() completion should run on the write pool",
            threadName.get(), startsWith("pantera-io-write-")
        );
    }

    @Test
    void identifierIncludesDelegate() {
        final DispatchedStorage direct = new DispatchedStorage(this.memory);
        assertThat(
            "identifier should contain delegate's identifier",
            direct.identifier(),
            containsString(this.memory.identifier())
        );
    }

    @Test
    void exclusivelyDelegatesCorrectly() throws Exception {
        final Key key = new Key.From("exclusive-key");
        this.memory.save(
            key,
            new Content.From("exclusive-data".getBytes(StandardCharsets.UTF_8))
        ).join();
        final Boolean result = this.storage.exclusively(
            key,
            (Storage sto) -> {
                final CompletionStage<Boolean> stage = sto.exists(key);
                return stage;
            }
        ).toCompletableFuture().get();
        assertThat(
            "exclusively() should delegate correctly and return result",
            result, is(true)
        );
    }

    /**
     * Storage wrapper that makes all operations genuinely asynchronous
     * by adding a small delay. This ensures delegate futures are not
     * already complete when the dispatch mechanism registers its callback,
     * making thread-pool assertions deterministic.
     */
    private static final class SlowStorage implements Storage {

        /**
         * Underlying storage.
         */
        private final Storage origin;

        /**
         * Ctor.
         * @param origin Delegate storage
         */
        SlowStorage(final Storage origin) {
            this.origin = origin;
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.delayed(this.origin.exists(key));
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.delayed(this.origin.list(prefix));
        }

        @Override
        public CompletableFuture<ListResult> list(
            final Key prefix, final String delimiter
        ) {
            return this.delayed(this.origin.list(prefix, delimiter));
        }

        @Override
        public CompletableFuture<Void> save(
            final Key key, final Content content
        ) {
            return this.delayed(this.origin.save(key, content));
        }

        @Override
        public CompletableFuture<Void> move(
            final Key source, final Key destination
        ) {
            return this.delayed(this.origin.move(source, destination));
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return this.delayed(this.origin.metadata(key));
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.delayed(this.origin.value(key));
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.delayed(this.origin.delete(key));
        }

        @Override
        public CompletableFuture<Void> deleteAll(final Key prefix) {
            return this.delayed(this.origin.deleteAll(prefix));
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key,
            final Function<Storage, CompletionStage<T>> operation
        ) {
            return this.origin.exclusively(key, operation);
        }

        @Override
        public String identifier() {
            return this.origin.identifier();
        }

        /**
         * Add a small async delay so the returned future is not already
         * completed when the caller receives it.
         * @param source Original future
         * @param <T> Result type
         * @return Delayed future
         */
        private <T> CompletableFuture<T> delayed(
            final CompletableFuture<? extends T> source
        ) {
            final CompletableFuture<T> result = new CompletableFuture<>();
            source.whenComplete(
                (val, err) -> CompletableFuture.runAsync(
                    () -> {
                        try {
                            Thread.sleep(10);
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        if (err != null) {
                            result.completeExceptionally(err);
                        } else {
                            result.complete(val);
                        }
                    }
                )
            );
            return result;
        }
    }
}
