/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.cache.RequestDeduplicator.FetchSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link RequestDeduplicator}.
 */
class RequestDeduplicatorTest {

    @Test
    @Timeout(5)
    void signalStrategyDeduplicatesConcurrentRequests() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final Key key = new Key.From("test/artifact.jar");
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final CompletableFuture<FetchSignal> blocker = new CompletableFuture<>();
        // First request: starts the fetch, blocks until we complete manually
        final CompletableFuture<FetchSignal> first = dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return blocker;
            }
        );
        // Second request for same key: should join the existing one
        final CompletableFuture<FetchSignal> second = dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
            }
        );
        assertThat("fetch should only run once", fetchCount.get(), equalTo(1));
        assertThat("first not done yet", first.isDone(), is(false));
        assertThat("second not done yet", second.isDone(), is(false));
        // Complete the fetch
        blocker.complete(FetchSignal.SUCCESS);
        assertThat(first.get(1, TimeUnit.SECONDS), equalTo(FetchSignal.SUCCESS));
        assertThat(second.get(1, TimeUnit.SECONDS), equalTo(FetchSignal.SUCCESS));
    }

    @Test
    @Timeout(5)
    void signalStrategyPropagatesNotFound() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final Key key = new Key.From("missing/artifact.jar");
        final CompletableFuture<FetchSignal> blocker = new CompletableFuture<>();
        final CompletableFuture<FetchSignal> first = dedup.deduplicate(
            key, () -> blocker
        );
        final CompletableFuture<FetchSignal> second = dedup.deduplicate(
            key, () -> CompletableFuture.completedFuture(FetchSignal.SUCCESS)
        );
        blocker.complete(FetchSignal.NOT_FOUND);
        assertThat(first.get(1, TimeUnit.SECONDS), equalTo(FetchSignal.NOT_FOUND));
        assertThat(second.get(1, TimeUnit.SECONDS), equalTo(FetchSignal.NOT_FOUND));
    }

    @Test
    @Timeout(5)
    void signalStrategyPropagatesError() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final Key key = new Key.From("error/artifact.jar");
        final CompletableFuture<FetchSignal> blocker = new CompletableFuture<>();
        final CompletableFuture<FetchSignal> first = dedup.deduplicate(
            key, () -> blocker
        );
        final CompletableFuture<FetchSignal> second = dedup.deduplicate(
            key, () -> CompletableFuture.completedFuture(FetchSignal.SUCCESS)
        );
        // Complete with exception — should signal ERROR
        blocker.completeExceptionally(new RuntimeException("upstream down"));
        assertThat(first.get(1, TimeUnit.SECONDS), equalTo(FetchSignal.ERROR));
        assertThat(second.get(1, TimeUnit.SECONDS), equalTo(FetchSignal.ERROR));
    }

    @Test
    @Timeout(5)
    void signalStrategyCleansUpAfterCompletion() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final Key key = new Key.From("cleanup/artifact.jar");
        assertThat("initially empty", dedup.inFlightCount(), equalTo(0));
        final CompletableFuture<FetchSignal> blocker = new CompletableFuture<>();
        dedup.deduplicate(key, () -> blocker);
        assertThat("one in-flight", dedup.inFlightCount(), equalTo(1));
        blocker.complete(FetchSignal.SUCCESS);
        // Allow async cleanup
        Thread.sleep(50);
        assertThat("cleaned up", dedup.inFlightCount(), equalTo(0));
    }

    @Test
    @Timeout(5)
    void signalStrategyAllowsNewRequestAfterCompletion() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final Key key = new Key.From("reuse/artifact.jar");
        final AtomicInteger fetchCount = new AtomicInteger(0);
        // First request
        final CompletableFuture<FetchSignal> first = dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
            }
        );
        first.get(1, TimeUnit.SECONDS);
        Thread.sleep(50);
        // Second request for same key after completion — should start new fetch
        final CompletableFuture<FetchSignal> second = dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
            }
        );
        second.get(1, TimeUnit.SECONDS);
        assertThat("should have fetched twice", fetchCount.get(), equalTo(2));
    }

    @Test
    @Timeout(5)
    void noneStrategyDoesNotDeduplicate() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.NONE);
        final Key key = new Key.From("none/artifact.jar");
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(1);
        final CompletableFuture<FetchSignal> first = dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return FetchSignal.SUCCESS;
                });
            }
        );
        final CompletableFuture<FetchSignal> second = dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
            }
        );
        // Both should have been called (no dedup)
        second.get(1, TimeUnit.SECONDS);
        assertThat("both fetches should have been invoked", fetchCount.get(), equalTo(2));
        latch.countDown();
        first.get(1, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(5)
    void storageStrategyDoesNotDeduplicate() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.STORAGE);
        final Key key = new Key.From("storage/artifact.jar");
        final AtomicInteger fetchCount = new AtomicInteger(0);
        dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
            }
        ).get(1, TimeUnit.SECONDS);
        dedup.deduplicate(
            key,
            () -> {
                fetchCount.incrementAndGet();
                return CompletableFuture.completedFuture(FetchSignal.SUCCESS);
            }
        ).get(1, TimeUnit.SECONDS);
        assertThat("STORAGE strategy delegates each call", fetchCount.get(), equalTo(2));
    }

    @Test
    @Timeout(5)
    void shutdownStopsCleanupAndClearsInFlight() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final CompletableFuture<FetchSignal> neverComplete = new CompletableFuture<>();
        final CompletableFuture<FetchSignal> result = dedup.deduplicate(
            new Key.From("shutdown/test"), () -> neverComplete
        );
        assertThat("one in-flight before shutdown", dedup.inFlightCount(), equalTo(1));
        dedup.shutdown();
        assertThat("in-flight cleared after shutdown", dedup.inFlightCount(), equalTo(0));
        assertThat("result is done", result.isDone(), is(true));
        assertThat("result is ERROR", result.join(), equalTo(FetchSignal.ERROR));
    }

    @Test
    @Timeout(5)
    void closeIsIdempotent() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        dedup.close();
        dedup.close();
        assertThat("double close does not throw", true, is(true));
    }

    @Test
    void differentKeysAreNotDeduplicated() throws Exception {
        final RequestDeduplicator dedup = new RequestDeduplicator(DedupStrategy.SIGNAL);
        final AtomicInteger fetchCount = new AtomicInteger(0);
        final CompletableFuture<FetchSignal> blocker1 = new CompletableFuture<>();
        final CompletableFuture<FetchSignal> blocker2 = new CompletableFuture<>();
        dedup.deduplicate(
            new Key.From("key1"), () -> {
                fetchCount.incrementAndGet();
                return blocker1;
            }
        );
        dedup.deduplicate(
            new Key.From("key2"), () -> {
                fetchCount.incrementAndGet();
                return blocker2;
            }
        );
        assertThat("different keys should both fetch", fetchCount.get(), equalTo(2));
        blocker1.complete(FetchSignal.SUCCESS);
        blocker2.complete(FetchSignal.SUCCESS);
    }
}
