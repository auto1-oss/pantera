/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.FailedCompletionStage;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.Repository;
import org.cactoos.set.SetOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link ComposerStorageCache}.
 */
final class ComposerStorageCacheTest {

    private Storage storage;

    private Repository repo;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.repo = new AstoRepository(this.storage);
    }

    @Test
    void getsContentFromRemoteCachesItAndSaveKeyToCacheFile() {
        final byte[] body = "some info".getBytes();
        final String key = "p2/vendor/package";
        MatcherAssert.assertThat(
            "Content was not obtained from remote",
            new ComposerStorageCache(this.repo).load(
                new Key.From(key),
                () -> CompletableFuture.completedFuture(Optional.of(new Content.From(body))),
                CacheControl.Standard.ALWAYS
            ).toCompletableFuture().join().orElseThrow().asBytes(),
            new IsEqual<>(body)
        );
        MatcherAssert.assertThat(
            "Item was not cached",
            this.storage.exists(
                new Key.From(String.format("%s.json", key))
            ).toCompletableFuture().join(),
            new IsEqual<>(true)
        );
        // NOTE: No longer using cache-info.json file
        // Timestamps are now tracked via filesystem metadata
    }

    @Test
    void getsContentFromCache() {
        final byte[] body = "some info".getBytes();
        final String key = "p2/vendor/package";
        // Save the cached content (filesystem timestamp is auto-created)
        this.storage.save(
            new Key.From(String.format("%s.json", key)),
            new Content.From(body)
        ).join();
        MatcherAssert.assertThat(
            new ComposerStorageCache(this.repo).load(
                new Key.From(key),
                () -> CompletableFuture.completedFuture(Optional.empty()),
                new CacheTimeControl(this.storage)
            ).toCompletableFuture().join().orElseThrow().asBytes(),
            new IsEqual<>(body)
        );
    }

    @Test
    void getsContentFromRemoteForExpiredCacheAndOverwriteValues() {
        final byte[] body = "some info".getBytes();
        final byte[] updated = "updated some info".getBytes();
        final String key = "p2/vendor/package";
        // Save old cached content
        this.storage.save(
            new Key.From(String.format("%s.json", key)),
            new Content.From(body)
        ).join();
        // Use a cache control that always invalidates (returns false)
        final CacheControl noCache = (item, content) -> CompletableFuture.completedFuture(false);
        MatcherAssert.assertThat(
            "Content was not obtained from remote when cache is invalidated",
            new ComposerStorageCache(this.repo).load(
                new Key.From(key),
                () -> CompletableFuture.completedFuture(Optional.of(new Content.From(updated))),
                noCache  // Invalidate cache, force re-fetch
            ).toCompletableFuture().join().orElseThrow().asBytes(),
            new IsEqual<>(updated)
        );
        MatcherAssert.assertThat(
            "Cached item was not overwritten",
            this.storage.value(
                new Key.From(String.format("%s.json", key))
            ).join().asBytes(),
            new IsEqual<>(updated)
        );
    }

    @Test
    void returnsEmptyOnRemoteErrorAndEmptyCache() {
        MatcherAssert.assertThat(
            "Was not empty for remote error and empty cache",
            new ComposerStorageCache(this.repo).load(
                new Key.From("anykey"),
                new Remote.WithErrorHandling(
                    () -> new FailedCompletionStage<>(
                        new IllegalStateException("Failed to obtain item from cache")
                    )
                ),
                CacheControl.Standard.ALWAYS
            ).toCompletableFuture().join()
            .isPresent(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "Cache storage is not empty",
            this.storage.list(Key.ROOT)
                .join().isEmpty(),
            new IsEqual<>(true)
        );
    }

    // NOTE: saveCacheFile() methods removed - no longer using cache-info.json
    // Filesystem timestamps are now used for cache time tracking
}
