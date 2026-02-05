/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.proxy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link BackpressureController}.
 */
class BackpressureControllerTest {

    @Test
    void executesOperationWhenPermitAvailable() throws Exception {
        final BackpressureController controller = new BackpressureController(
            10, Duration.ofSeconds(5), "test"
        );

        final String result = controller.execute(
            () -> CompletableFuture.completedFuture("success")
        ).join();

        assertEquals("success", result);
        assertEquals(0, controller.activeCount());
        assertEquals(1, controller.completedCount());
    }

    @Test
    void limitsMaxConcurrentRequests() throws Exception {
        final int maxConcurrent = 2;
        final BackpressureController controller = new BackpressureController(
            maxConcurrent, Duration.ofSeconds(5), "test"
        );

        final CountDownLatch started = new CountDownLatch(maxConcurrent);
        final CountDownLatch canFinish = new CountDownLatch(1);
        final AtomicInteger maxActive = new AtomicInteger(0);

        final List<CompletableFuture<String>> futures = new ArrayList<>();

        // Start max + 1 requests
        for (int i = 0; i < maxConcurrent + 1; i++) {
            futures.add(controller.execute(() -> {
                started.countDown();
                maxActive.updateAndGet(current ->
                    Math.max(current, (int) controller.activeCount())
                );
                try {
                    canFinish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture("done");
            }));
        }

        // Wait for initial requests to start
        Thread.sleep(100);

        // Only maxConcurrent should be active, one is queued
        assertTrue(controller.activeCount() <= maxConcurrent,
            "Active count should be <= max: " + controller.activeCount());

        // Let all complete
        canFinish.countDown();

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        assertTrue(maxActive.get() <= maxConcurrent,
            "Max active should never exceed limit: " + maxActive.get());
    }

    @Test
    void tryExecuteRejectsWhenNoPermit() throws Exception {
        final BackpressureController controller = new BackpressureController(
            1, Duration.ofSeconds(5), "test"
        );

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch canFinish = new CountDownLatch(1);

        // Start a long-running request in a separate thread
        final CompletableFuture<String> first = CompletableFuture.supplyAsync(() ->
            controller.execute(() -> {
                started.countDown();
                try {
                    canFinish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture("first");
            }).join()
        );

        // Wait for first request to actually start and acquire permit
        assertTrue(started.await(5, TimeUnit.SECONDS), "First request should start");

        // Try to execute another - should be rejected
        assertThrows(java.util.concurrent.CompletionException.class, () ->
            controller.tryExecute(() ->
                CompletableFuture.completedFuture("second")
            ).join()
        );

        assertEquals(1, controller.rejectedCount(), "Should have rejected one request");

        // Cleanup
        canFinish.countDown();
        first.join();
    }

    @Test
    void executeWaitsInQueue() throws Exception {
        final BackpressureController controller = new BackpressureController(
            1, Duration.ofSeconds(5), "test"
        );

        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch canFinish = new CountDownLatch(1);

        // Start first request in a separate thread (so it doesn't block test thread)
        final CompletableFuture<String> first = CompletableFuture.supplyAsync(() ->
            controller.execute(() -> {
                firstStarted.countDown();
                try {
                    canFinish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture("first");
            }).join()
        );

        // Wait for first to start
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "First should start");

        // Start second request (should queue) in a separate thread
        final CompletableFuture<String> second = CompletableFuture.supplyAsync(() ->
            controller.execute(() -> CompletableFuture.completedFuture("second")).join()
        );

        // Give time to queue
        Thread.sleep(100);

        // Let first complete
        canFinish.countDown();
        first.join();

        // Second should now complete
        assertEquals("second", second.join());
        assertEquals(0, controller.queuedCount());
        assertEquals(2, controller.completedCount());
    }

    @Test
    void rejectsOnQueueTimeout() throws Exception {
        final BackpressureController controller = new BackpressureController(
            1, Duration.ofMillis(100), "test"  // Short timeout
        );

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch canFinish = new CountDownLatch(1);

        // Start a long-running request in a separate thread
        final CompletableFuture<String> first = CompletableFuture.supplyAsync(() ->
            controller.execute(() -> {
                started.countDown();
                try {
                    canFinish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CompletableFuture.completedFuture("first");
            }).join()
        );

        // Wait for first to start
        assertTrue(started.await(5, TimeUnit.SECONDS), "First should start");

        // Try to queue another - should timeout and reject
        assertThrows(java.util.concurrent.CompletionException.class, () ->
            controller.execute(() ->
                CompletableFuture.completedFuture("second")
            ).join()
        );

        assertTrue(controller.rejectedCount() > 0, "Should have rejected request");

        // Cleanup
        canFinish.countDown();
        first.join();
    }

    @Test
    void releasesPermitOnException() throws Exception {
        final BackpressureController controller = new BackpressureController(
            1, Duration.ofSeconds(5), "test"
        );

        // Execute operation that throws
        assertThrows(java.util.concurrent.CompletionException.class, () ->
            controller.execute(() ->
                CompletableFuture.failedFuture(new RuntimeException("test error"))
            ).join()
        );

        // Permit should be released
        assertEquals(0, controller.activeCount());
        assertEquals(1, controller.availablePermits());

        // Should be able to execute another
        final String result = controller.execute(() ->
            CompletableFuture.completedFuture("success")
        ).join();

        assertEquals("success", result);
    }

    @Test
    void utilizationCalculation() throws Exception {
        final BackpressureController controller = new BackpressureController(
            10, Duration.ofSeconds(5), "test"
        );

        assertEquals(0.0, controller.utilization(), 0.01);

        final CountDownLatch allStarted = new CountDownLatch(5);
        final CountDownLatch canFinish = new CountDownLatch(1);

        // Start 5 requests in separate threads (so they don't block test thread)
        final List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            futures.add(CompletableFuture.supplyAsync(() ->
                controller.execute(() -> {
                    allStarted.countDown();
                    try {
                        canFinish.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return CompletableFuture.completedFuture("done");
                }).join()
            ));
        }

        // Wait for all to start
        assertTrue(allStarted.await(5, TimeUnit.SECONDS), "All should start");

        assertEquals(0.5, controller.utilization(), 0.01);

        // Cleanup
        canFinish.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            new BackpressureController(0, Duration.ofSeconds(5), "test")
        );
    }
}
