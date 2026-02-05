/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DistributedInFlight}.
 */
class DistributedInFlightTest {

    @Test
    void firstRequestBecomesLeader() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofSeconds(90),
            Optional.empty()  // Local only
        );

        final DistributedInFlight.InFlightResult result = inFlight.tryAcquire("key1").join();
        assertTrue(result.isLeader(), "First request should be leader");
        assertFalse(result.isWaiter(), "First request should not be waiter");
        assertEquals(1, inFlight.inFlightCount());

        result.complete(true);
        assertEquals(0, inFlight.inFlightCount());
    }

    @Test
    void secondRequestBecomesWaiter() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofSeconds(90),
            Optional.empty()
        );

        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire("key1").join();
        assertTrue(leader.isLeader());

        final DistributedInFlight.InFlightResult waiter = inFlight.tryAcquire("key1").join();
        assertTrue(waiter.isWaiter(), "Second request should be waiter");
        assertFalse(waiter.isLeader(), "Second request should not be leader");

        leader.complete(true);
    }

    @Test
    void waiterGetsNotifiedOnSuccess() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofSeconds(90),
            Optional.empty()
        );

        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire("key1").join();
        final DistributedInFlight.InFlightResult waiter = inFlight.tryAcquire("key1").join();

        final CompletableFuture<Boolean> waiterResult = waiter.waitForLeader();
        assertFalse(waiterResult.isDone(), "Waiter should still be waiting");

        leader.complete(true);

        final Boolean success = waiterResult.get(1, TimeUnit.SECONDS);
        assertTrue(success, "Waiter should receive success signal");
    }

    @Test
    void waiterGetsNotifiedOnFailure() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofSeconds(90),
            Optional.empty()
        );

        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire("key1").join();
        final DistributedInFlight.InFlightResult waiter = inFlight.tryAcquire("key1").join();

        final CompletableFuture<Boolean> waiterResult = waiter.waitForLeader();

        leader.complete(false);

        final Boolean success = waiterResult.get(1, TimeUnit.SECONDS);
        assertFalse(success, "Waiter should receive failure signal");
    }

    @Test
    void differentKeysAreIndependent() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofSeconds(90),
            Optional.empty()
        );

        final DistributedInFlight.InFlightResult result1 = inFlight.tryAcquire("key1").join();
        final DistributedInFlight.InFlightResult result2 = inFlight.tryAcquire("key2").join();

        assertTrue(result1.isLeader(), "First key should have leader");
        assertTrue(result2.isLeader(), "Second key should have leader (different key)");
        assertEquals(2, inFlight.inFlightCount());

        result1.complete(true);
        result2.complete(true);
        assertEquals(0, inFlight.inFlightCount());
    }

    @Test
    void concurrentRequestsOnSameKeyAreDeduplicated() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofSeconds(90),
            Optional.empty()
        );

        final int numRequests = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch resultsLatch = new CountDownLatch(numRequests);
        final AtomicInteger leaderCount = new AtomicInteger(0);
        final AtomicInteger waiterCount = new AtomicInteger(0);

        final List<CompletableFuture<DistributedInFlight.InFlightResult>> futures = new ArrayList<>();

        for (int i = 0; i < numRequests; i++) {
            final CompletableFuture<DistributedInFlight.InFlightResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    startLatch.await();
                    return inFlight.tryAcquire("key1").join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });

            future.thenAccept(result -> {
                if (result.isLeader()) {
                    leaderCount.incrementAndGet();
                } else {
                    waiterCount.incrementAndGet();
                }
                resultsLatch.countDown();
            });

            futures.add(future);
        }

        // Start all requests simultaneously
        startLatch.countDown();

        // Wait for all to complete
        assertTrue(resultsLatch.await(5, TimeUnit.SECONDS));

        assertEquals(1, leaderCount.get(), "Should have exactly one leader");
        assertEquals(numRequests - 1, waiterCount.get(), "Rest should be waiters");

        // Complete leader
        for (CompletableFuture<DistributedInFlight.InFlightResult> future : futures) {
            final DistributedInFlight.InFlightResult result = future.get();
            if (result.isLeader()) {
                result.complete(true);
                break;
            }
        }
    }

    @Test
    void timeoutReleasesEntry() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "test",
            Duration.ofMillis(100),  // Short timeout
            Optional.empty()
        );

        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire("key1").join();
        assertTrue(leader.isLeader());
        assertEquals(1, inFlight.inFlightCount());

        // Wait for timeout
        Thread.sleep(200);

        // Entry should be cleaned up after timeout
        // Note: cleanup happens on next acquire or when completion fires
        assertEquals(0, inFlight.inFlightCount());

        // New request should become leader
        final DistributedInFlight.InFlightResult newLeader = inFlight.tryAcquire("key1").join();
        assertTrue(newLeader.isLeader(), "Should become leader after timeout");
    }

    @Test
    void namespaceIsolatesKeys() throws Exception {
        final DistributedInFlight inFlight1 = new DistributedInFlight(
            "namespace1",
            Duration.ofSeconds(90),
            Optional.empty()
        );
        final DistributedInFlight inFlight2 = new DistributedInFlight(
            "namespace2",
            Duration.ofSeconds(90),
            Optional.empty()
        );

        final DistributedInFlight.InFlightResult result1 = inFlight1.tryAcquire("key1").join();
        final DistributedInFlight.InFlightResult result2 = inFlight2.tryAcquire("key1").join();

        assertTrue(result1.isLeader(), "Namespace1 should have leader");
        assertTrue(result2.isLeader(), "Namespace2 should have leader (different namespace)");

        result1.complete(true);
        result2.complete(true);
    }
}
