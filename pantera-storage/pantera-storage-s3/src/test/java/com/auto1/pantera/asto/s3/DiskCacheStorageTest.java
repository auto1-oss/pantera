/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DiskCacheStorage}.
 */
final class DiskCacheStorageTest {

    @Test
    @Timeout(5)
    void cacheHitWithoutValidation(@TempDir final Path tmp) throws Exception {
        final InMemoryStorage delegate = new InMemoryStorage();
        final Key key = new Key.From("test/artifact.jar");
        final byte[] data = "test-content".getBytes();
        delegate.save(key, new Content.From(data)).join();
        final DiskCacheStorage cache = new DiskCacheStorage(
            delegate, tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU,
            0, 90, 80, false
        );
        // First call populates cache
        final byte[] first = cache.value(key).join().asBytes();
        MatcherAssert.assertThat(first, Matchers.equalTo(data));
        // Second call should be served from disk cache
        final byte[] second = cache.value(key).join().asBytes();
        MatcherAssert.assertThat(second, Matchers.equalTo(data));
        cache.close();
    }

    @Test
    @Timeout(5)
    void cacheMissFetchesFromDelegate(@TempDir final Path tmp) throws Exception {
        final InMemoryStorage delegate = new InMemoryStorage();
        final Key key = new Key.From("missing/file.txt");
        final byte[] data = "fetched-from-delegate".getBytes();
        delegate.save(key, new Content.From(data)).join();
        final DiskCacheStorage cache = new DiskCacheStorage(
            delegate, tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU,
            0, 90, 80, false
        );
        final byte[] result = cache.value(key).join().asBytes();
        MatcherAssert.assertThat(result, Matchers.equalTo(data));
        cache.close();
    }

    @Test
    @Timeout(10)
    void validationTimeoutDoesNotBlock(@TempDir final Path tmp) throws Exception {
        final Storage slow = new SlowMetadataStorage();
        final Key key = new Key.From("slow/artifact.jar");
        final byte[] data = "slow-content".getBytes();
        slow.save(key, new Content.From(data)).join();
        // Enable validation - the slow delegate will timeout
        final DiskCacheStorage cache = new DiskCacheStorage(
            slow, tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU,
            0, 90, 80, true
        );
        // First call populates cache from delegate
        final byte[] first = cache.value(key).join().asBytes();
        MatcherAssert.assertThat(first, Matchers.equalTo(data));
        // Second call: cache file exists but validation will timeout.
        // Should complete within 10s (the @Timeout), not hang indefinitely.
        // With the old blocking .join(), this could hang the event loop.
        final long start = System.currentTimeMillis();
        final byte[] second = cache.value(key).join().asBytes();
        final long elapsed = System.currentTimeMillis() - start;
        // Should re-fetch since validation timed out (assumes stale)
        MatcherAssert.assertThat(second, Matchers.equalTo(data));
        cache.close();
    }

    @Test
    @Timeout(5)
    void saveInvalidatesCache(@TempDir final Path tmp) throws Exception {
        final InMemoryStorage delegate = new InMemoryStorage();
        final Key key = new Key.From("update/file.txt");
        delegate.save(key, new Content.From("v1".getBytes())).join();
        final DiskCacheStorage cache = new DiskCacheStorage(
            delegate, tmp, 1024 * 1024, DiskCacheStorage.Policy.LRU,
            0, 90, 80, false
        );
        // Populate cache
        cache.value(key).join().asBytes();
        // Update via cache (which invalidates)
        cache.save(key, new Content.From("v2".getBytes())).join();
        // Should fetch fresh from delegate
        final byte[] result = cache.value(key).join().asBytes();
        MatcherAssert.assertThat(result, Matchers.equalTo("v2".getBytes()));
        cache.close();
    }

    /**
     * Storage with slow metadata() that never completes,
     * simulating S3 connectivity issues.
     */
    private static final class SlowMetadataStorage implements Storage {
        private final InMemoryStorage inner = new InMemoryStorage();

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            return this.inner.exists(key);
        }

        @Override
        public CompletableFuture<Collection<Key>> list(final Key prefix) {
            return this.inner.list(prefix);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            return this.inner.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key dest) {
            return this.inner.move(source, dest);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            // Never completes - simulates network hang
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Content> value(final Key key) {
            return this.inner.value(key);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            return this.inner.delete(key);
        }

        @Override
        public <T> CompletionStage<T> exclusively(
            final Key key,
            final Function<Storage, CompletionStage<T>> op
        ) {
            return this.inner.exclusively(key, op);
        }

        @Override
        public String identifier() {
            return "slow-test";
        }
    }
}
