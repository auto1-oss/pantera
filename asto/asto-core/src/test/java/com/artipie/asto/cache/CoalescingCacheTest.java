/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.cache;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test for {@link CoalescingCache}.
 */
class CoalescingCacheTest {

    @Test
    void coalescesConcurrentRequests() throws Exception {
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final CountDownLatch startFetch = new CountDownLatch(1);
        final CountDownLatch finishFetch = new CountDownLatch(1);

        final Cache underlying = new Cache() {
            @Override
            public CompletableFuture<Optional<? extends Content>> load(
                    Key key, Remote remote, CacheControl control) {
                fetchCount.incrementAndGet();
                return CompletableFuture.supplyAsync(() -> {
                    startFetch.countDown();
                    try {
                        // Simulate slow fetch
                        finishFetch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Optional.empty();
                });
            }
        };

        final Cache cache = new CoalescingCache(underlying);
        final Key key = new Key.From("test");

        // Start 5 concurrent requests for the same key
        final CompletableFuture<?>[] futures = new CompletableFuture[5];
        for (int i = 0; i < 5; i++) {
            futures[i] = cache.load(
                    key,
                    () -> CompletableFuture.completedFuture(Optional.empty()),
                    (k, supplier) -> CompletableFuture.completedFuture(false)).toCompletableFuture();
        }

        // Wait for first fetch to start
        startFetch.await(5, TimeUnit.SECONDS);

        // Only one fetch should have been initiated
        MatcherAssert.assertThat(
                "Only one upstream fetch should occur",
                fetchCount.get(),
                Matchers.equalTo(1));

        // Complete the fetch
        finishFetch.countDown();

        // All futures should complete
        CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);

        // Still only one fetch
        MatcherAssert.assertThat(
                "Still only one upstream fetch after all complete",
                fetchCount.get(),
                Matchers.equalTo(1));
    }

    @Test
    void allowsConcurrentRequestsForDifferentKeys() throws Exception {
        final AtomicInteger fetchCount = new AtomicInteger(0);

        final Cache underlying = new Cache() {
            @Override
            public CompletableFuture<Optional<? extends Content>> load(
                    Key key, Remote remote, CacheControl control) {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };

        final Cache cache = new CoalescingCache(underlying);

        // Start requests for different keys
        final CompletableFuture<?> f1 = cache.load(
                new Key.From("key1"),
                () -> CompletableFuture.completedFuture(Optional.empty()),
                (k, supplier) -> CompletableFuture.completedFuture(false)).toCompletableFuture();

        final CompletableFuture<?> f2 = cache.load(
                new Key.From("key2"),
                () -> CompletableFuture.completedFuture(Optional.empty()),
                (k, supplier) -> CompletableFuture.completedFuture(false)).toCompletableFuture();

        CompletableFuture.allOf(f1, f2).get(5, TimeUnit.SECONDS);

        // Different keys should result in separate fetches
        MatcherAssert.assertThat(
                "Different keys should each trigger a fetch",
                fetchCount.get(),
                Matchers.equalTo(2));
    }
}
