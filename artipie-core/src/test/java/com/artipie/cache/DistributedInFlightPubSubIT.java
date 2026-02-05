/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link DistributedInFlight} Pub/Sub notification.
 * Verifies that waiters receive instant notifications via Pub/Sub instead of polling.
 * Requires Docker to be running.
 */
@Testcontainers
@EnabledIf("isDockerAvailable")
class DistributedInFlightPubSubIT {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    private static ValkeyConnection connection;

    @BeforeAll
    static void setUp() {
        REDIS.start();
        connection = new ValkeyConnection(
            REDIS.getHost(),
            REDIS.getFirstMappedPort(),
            Duration.ofSeconds(5)
        );
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void waiterReceivesInstantNotificationViaPublish() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "pubsub-test",
            Duration.ofSeconds(30),
            Optional.of(connection)
        );

        // Leader acquires the lock
        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire("key1").join();
        assertTrue(leader.isLeader(), "First request should be leader");

        // Waiter tries to acquire (should become waiter and subscribe to channel)
        final DistributedInFlight.InFlightResult waiter = inFlight.tryAcquire("key1").join();
        assertTrue(waiter.isWaiter(), "Second request should be waiter");

        // Start waiting for leader
        final CompletableFuture<Boolean> waiterResult = waiter.waitForLeader();
        assertFalse(waiterResult.isDone(), "Waiter should be waiting");

        // Measure notification latency
        final long startTime = System.currentTimeMillis();

        // Leader completes successfully
        leader.complete(true).join();

        // Waiter should receive notification almost instantly via Pub/Sub
        final Boolean success = waiterResult.get(5, TimeUnit.SECONDS);
        final long latency = System.currentTimeMillis() - startTime;

        assertTrue(success, "Waiter should receive success notification");
        // With Pub/Sub, latency should be under 100ms (vs 10-500ms with polling)
        assertTrue(latency < 500, "Notification should be near-instant via Pub/Sub, got: " + latency + "ms");
    }

    @Test
    void multipleWaitersReceiveNotification() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "pubsub-multi-test",
            Duration.ofSeconds(30),
            Optional.of(connection)
        );

        final String key = "multi-waiter-key";
        final int numWaiters = 5;

        // Leader acquires
        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire(key).join();
        assertTrue(leader.isLeader());

        // Create multiple waiters
        final List<CompletableFuture<Boolean>> waiterFutures = new ArrayList<>();
        for (int i = 0; i < numWaiters; i++) {
            final DistributedInFlight.InFlightResult waiter = inFlight.tryAcquire(key).join();
            assertTrue(waiter.isWaiter(), "Request " + i + " should be waiter");
            waiterFutures.add(waiter.waitForLeader());
        }

        // All waiters should be waiting
        for (CompletableFuture<Boolean> future : waiterFutures) {
            assertFalse(future.isDone(), "Waiter should be waiting");
        }

        // Leader completes
        final long startTime = System.currentTimeMillis();
        leader.complete(true).join();

        // All waiters should be notified via Pub/Sub
        final CountDownLatch latch = new CountDownLatch(numWaiters);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicLong maxLatency = new AtomicLong(0);

        for (CompletableFuture<Boolean> future : waiterFutures) {
            future.thenAccept(success -> {
                final long latency = System.currentTimeMillis() - startTime;
                maxLatency.updateAndGet(current -> Math.max(current, latency));
                if (success) {
                    successCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All waiters should be notified");
        assertEquals(numWaiters, successCount.get(), "All waiters should receive success");
        assertTrue(maxLatency.get() < 500, "Max notification latency should be under 500ms, got: " + maxLatency.get() + "ms");
    }

    @Test
    void waiterReceivesFailureNotification() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "pubsub-failure-test",
            Duration.ofSeconds(30),
            Optional.of(connection)
        );

        // Leader acquires
        final DistributedInFlight.InFlightResult leader = inFlight.tryAcquire("fail-key").join();
        assertTrue(leader.isLeader());

        // Waiter subscribes
        final DistributedInFlight.InFlightResult waiter = inFlight.tryAcquire("fail-key").join();
        assertTrue(waiter.isWaiter());

        final CompletableFuture<Boolean> waiterResult = waiter.waitForLeader();

        // Give time for subscription to be established in Redis
        // This is necessary because subscription happens asynchronously
        Thread.sleep(100);

        // Leader fails
        leader.complete(false).join();

        // Waiter should receive failure notification
        final Boolean success = waiterResult.get(5, TimeUnit.SECONDS);
        assertFalse(success, "Waiter should receive failure notification");
    }

    @Test
    void concurrentDistributedDeduplication() throws Exception {
        final DistributedInFlight inFlight = new DistributedInFlight(
            "pubsub-concurrent-test",
            Duration.ofSeconds(30),
            Optional.of(connection)
        );

        final int numRequests = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(numRequests);
        final AtomicInteger leaderCount = new AtomicInteger(0);
        final AtomicInteger waiterCount = new AtomicInteger(0);

        final List<CompletableFuture<DistributedInFlight.InFlightResult>> futures = new ArrayList<>();

        for (int i = 0; i < numRequests; i++) {
            final CompletableFuture<DistributedInFlight.InFlightResult> future =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        startLatch.await();
                        return inFlight.tryAcquire("concurrent-key").join();
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
                doneLatch.countDown();
            });

            futures.add(future);
        }

        // Start all requests simultaneously
        startLatch.countDown();

        // Wait for all to acquire
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All requests should complete");

        // Exactly one leader
        assertEquals(1, leaderCount.get(), "Should have exactly one leader");
        assertEquals(numRequests - 1, waiterCount.get(), "Rest should be waiters");

        // Complete leader
        for (CompletableFuture<DistributedInFlight.InFlightResult> future : futures) {
            final DistributedInFlight.InFlightResult result = future.get();
            if (result.isLeader()) {
                result.complete(true).join();
                break;
            }
        }
    }

    static boolean isDockerAvailable() {
        try {
            final Process process = Runtime.getRuntime().exec(new String[]{"docker", "info"});
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
