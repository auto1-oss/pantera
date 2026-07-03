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
package com.auto1.pantera.api.v1;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StorageMetaCache}.
 */
final class StorageMetaCacheTest {

    @Test
    void putAndGetReturnsStoredEntry() {
        final StorageMetaCache cache = new StorageMetaCache();
        cache.put("my-repo", "path/to/file.zip", 1024L, "2026-01-01T00:00:00Z");
        final Optional<StorageMetaCache.Entry> result = cache.get("my-repo", "path/to/file.zip");
        Assertions.assertTrue(result.isPresent(), "Cache should contain the stored entry");
        Assertions.assertEquals(1024L, result.get().size());
        Assertions.assertEquals("2026-01-01T00:00:00Z", result.get().modifiedIso());
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        final StorageMetaCache cache = new StorageMetaCache();
        final Optional<StorageMetaCache.Entry> result = cache.get("no-repo", "nonexistent/path");
        Assertions.assertFalse(result.isPresent(), "Cache miss should return Optional.empty()");
    }

    @Test
    void invalidateRemovesEntry() {
        final StorageMetaCache cache = new StorageMetaCache();
        cache.put("repo-a", "file.tar.gz", 512L, "2026-02-01T00:00:00Z");
        cache.invalidate("repo-a", "file.tar.gz");
        Assertions.assertFalse(
            cache.get("repo-a", "file.tar.gz").isPresent(),
            "Entry should be removed after invalidate"
        );
    }

    @Test
    void invalidatePrefixRemovesMatchingKeysAndLeavesOthers() {
        final StorageMetaCache cache = new StorageMetaCache();
        cache.put("pkg-repo", "com/example/1.0/a.jar", 100L, null);
        cache.put("pkg-repo", "com/example/1.0/b.jar", 200L, null);
        cache.put("pkg-repo", "com/other/1.0/c.jar", 300L, null);
        cache.put("other-repo", "com/example/1.0/d.jar", 400L, null);
        cache.invalidatePrefix("pkg-repo", "com/example/");
        Assertions.assertFalse(
            cache.get("pkg-repo", "com/example/1.0/a.jar").isPresent(),
            "a.jar under the prefix should be evicted"
        );
        Assertions.assertFalse(
            cache.get("pkg-repo", "com/example/1.0/b.jar").isPresent(),
            "b.jar under the prefix should be evicted"
        );
        Assertions.assertTrue(
            cache.get("pkg-repo", "com/other/1.0/c.jar").isPresent(),
            "c.jar outside the prefix should remain"
        );
        Assertions.assertTrue(
            cache.get("other-repo", "com/example/1.0/d.jar").isPresent(),
            "d.jar in a different repo should remain"
        );
    }

    @Test
    void lruEvictionFiresWhenMaxEntriesExceeded() throws Exception {
        // Use package-private ctor with maxEntries=2 so the third put triggers LRU eviction.
        final StorageMetaCache cache = new StorageMetaCache(2, Duration.ofMinutes(10));
        cache.put("r", "a", 1L, null);
        cache.put("r", "b", 2L, null);
        cache.put("r", "c", 3L, null);
        // Caffeine eviction is asynchronous — cleanUp() forces it to complete.
        cache.cleanUp();
        // At most 2 entries should remain; the LRU one (a) should be gone.
        // We verify that not all three are present (eviction has fired).
        final boolean allThreePresent =
            cache.get("r", "a").isPresent()
            && cache.get("r", "b").isPresent()
            && cache.get("r", "c").isPresent();
        Assertions.assertFalse(allThreePresent, "LRU eviction should remove the oldest entry");
    }

    @Test
    void ttlExpiryMakesEntryDisappear() throws Exception {
        // Use package-private ctor with a very short TTL.
        final StorageMetaCache cache = new StorageMetaCache(1_000, Duration.ofMillis(50));
        cache.put("r", "expiry-test", 99L, "2026-01-01T00:00:00Z");
        Thread.sleep(120);
        // cleanUp() flushes Caffeine's lazy expiry queue.
        cache.cleanUp();
        Assertions.assertFalse(
            cache.get("r", "expiry-test").isPresent(),
            "Entry should have expired after TTL"
        );
    }
}
