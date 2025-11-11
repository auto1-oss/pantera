/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Test for {@link NegativeCache}.
 */
class NegativeCacheTest {

    @Test
    void cachesNotFoundAndReuses() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1));
        final Key key = new Key.From("missing.jar");
        
        MatcherAssert.assertThat("Initially not cached", cache.isNotFound(key), Matchers.is(false));
        
        cache.cacheNotFound(key);
        
        MatcherAssert.assertThat("Now cached as not found", cache.isNotFound(key), Matchers.is(true));
    }

    @Test
    void expiresAfterTtl() throws Exception {
        final NegativeCache cache = new NegativeCache(Duration.ofMillis(100));
        final Key key = new Key.From("missing.jar");
        
        cache.cacheNotFound(key);
        MatcherAssert.assertThat("Cached", cache.isNotFound(key), Matchers.is(true));
        
        Thread.sleep(150);
        
        MatcherAssert.assertThat("Expired", cache.isNotFound(key), Matchers.is(false));
    }

    @Test
    void invalidateRemovesEntry() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1));
        final Key key = new Key.From("missing.jar");
        
        cache.cacheNotFound(key);
        MatcherAssert.assertThat("Cached", cache.isNotFound(key), Matchers.is(true));
        
        cache.invalidate(key);
        
        MatcherAssert.assertThat("Invalidated", cache.isNotFound(key), Matchers.is(false));
    }

    @Test
    void cleansUpExpiredEntries() throws Exception {
        final NegativeCache cache = new NegativeCache(Duration.ofMillis(50));
        
        cache.cacheNotFound(new Key.From("test1.jar"));
        cache.cacheNotFound(new Key.From("test2.jar"));
        
        MatcherAssert.assertThat("Size is 2", cache.size(), Matchers.is(2L));
        
        Thread.sleep(100);
        
        cache.cleanup();
        
        // After cleanup, expired entries should be removed
        MatcherAssert.assertThat("Size is 0 after cleanup", cache.size(), Matchers.is(0L));
    }

    @Test
    void disabledCacheNeverCaches() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), false);
        final Key key = new Key.From("missing.jar");
        
        cache.cacheNotFound(key);
        
        MatcherAssert.assertThat("Not cached when disabled", cache.isNotFound(key), Matchers.is(false));
        MatcherAssert.assertThat("Is disabled", cache.isEnabled(), Matchers.is(false));
    }

    @Test
    void invalidatePrefixRemovesMatchingEntries() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1));
        
        cache.cacheNotFound(new Key.From("com/example/artifact/1.0/test.jar"));
        cache.cacheNotFound(new Key.From("com/example/artifact/2.0/test.jar"));
        cache.cacheNotFound(new Key.From("com/other/package/1.0/test.jar"));
        
        MatcherAssert.assertThat("Size is 3", cache.size(), Matchers.is(3L));
        
        cache.invalidatePrefix("com/example/artifact/");
        
        MatcherAssert.assertThat("Size is 1 after prefix invalidation", cache.size(), Matchers.is(1L));
        MatcherAssert.assertThat(
            "Other package still cached",
            cache.isNotFound(new Key.From("com/other/package/1.0/test.jar")),
            Matchers.is(true)
        );
    }
}
