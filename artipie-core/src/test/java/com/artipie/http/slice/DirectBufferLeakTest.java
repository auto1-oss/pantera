/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.fs.FileStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.rq.RequestLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for direct buffer memory leak in FileSystemArtifactSlice.
 *
 * <p>This test reproduces the OOM incident from January 22, 2026 where
 * orphaned subscriptions leaked direct buffers because cleanup() was
 * never called when HTTP connections were abandoned.</p>
 *
 * @since 1.20.13
 */
class DirectBufferLeakTest {

    /**
     * Short timeout for testing (2 seconds instead of default 60).
     * Must be set BEFORE FileSystemArtifactSlice class is loaded.
     */
    private static final long TEST_TIMEOUT_SECONDS = 2;

    @org.junit.jupiter.api.BeforeAll
    static void setUpTimeout() {
        // Set short timeout for deterministic testing
        System.setProperty("artipie.filesystem.subscription.timeout",
            String.valueOf(TEST_TIMEOUT_SECONDS));
    }

    @TempDir
    Path tempDir;

    private Path testFile;
    private FileStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test file LARGER than chunk size (1MB) so subscription doesn't
        // complete immediately after first chunk. Using 3MB ensures multiple chunks needed.
        testFile = tempDir.resolve("test-artifact.jar");
        byte[] data = new byte[3 * 1024 * 1024]; // 3MB > 1MB chunk size
        Files.write(testFile, data);

        storage = new FileStorage(tempDir);
    }

    @AfterEach
    void tearDown() {
        // Force GC to attempt cleanup
        System.gc();
    }

    /**
     * Test that demonstrates the buffer leak bug.
     *
     * <p>When HTTP requests are abandoned (client disconnects without
     * consuming the body or calling cancel), the direct buffer allocated
     * in BackpressureFileSubscription is never released.</p>
     *
     * <p>This is the root cause of the January 22, 2026 OOM incident.</p>
     */
    @Test
    @Timeout(60)
    void orphanedSubscriptionsLeakDirectBuffers() throws Exception {
        final FileSystemArtifactSlice slice = new FileSystemArtifactSlice(storage);
        final int numRequests = 20;

        // Record baseline direct memory
        final long baseline = getDirectMemoryUsed();
        System.out.printf("Direct memory baseline: %d bytes%n", baseline);

        // Create subscriptions that request data (triggering buffer allocation)
        // but then abandon without cancelling or completing
        final List<AtomicReference<Subscription>> subscriptions = new ArrayList<>();
        final List<CountDownLatch> latches = new ArrayList<>();

        for (int i = 0; i < numRequests; i++) {
            Response response = slice.response(
                RequestLine.from("GET /test-artifact.jar HTTP/1.1"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);

            // Debug: Check response status
            if (i == 0) {
                System.out.printf("First request: status=%d, body class=%s%n",
                    response.status().code(),
                    response.body() != null ? response.body().getClass().getSimpleName() : "null");
            }

            // Skip if not found
            if (response.status().code() != 200) {
                fail("Expected 200 OK, got " + response.status().code() + " - file not found");
            }

            // Subscribe to body and request 1 chunk to trigger buffer allocation
            final CountDownLatch gotData = new CountDownLatch(1);
            final AtomicReference<Subscription> subRef = new AtomicReference<>();

            response.body().subscribe(new Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Subscription s) {
                    subRef.set(s);
                    // Request 1 chunk - this triggers drainLoop() and buffer allocation
                    s.request(1);
                }

                @Override
                public void onNext(ByteBuffer byteBuffer) {
                    // Got data - buffer was allocated
                    gotData.countDown();
                    // DON'T request more, DON'T cancel - simulate abandon
                }

                @Override
                public void onError(Throwable t) {
                    gotData.countDown();
                }

                @Override
                public void onComplete() {
                    gotData.countDown();
                }
            });

            // Wait for at least one chunk to ensure buffer allocation
            assertTrue(gotData.await(5, TimeUnit.SECONDS),
                "Should receive at least one chunk");

            subscriptions.add(subRef);
            latches.add(gotData);
        }

        // Give time for all buffer allocations to complete
        Thread.sleep(500);

        // Measure memory after allocations
        final long afterAllocation = getDirectMemoryUsed();
        final long allocated = afterAllocation - baseline;

        System.out.printf("Direct memory after allocation: %d bytes%n", afterAllocation);
        System.out.printf("Allocated during test: %d bytes (%.1f MB)%n",
            allocated, allocated / 1024.0 / 1024.0);

        // Verify buffers were allocated
        // Each request should allocate ~1MB (chunk size)
        assertTrue(allocated > numRequests * 500_000,
            "Expected at least " + (numRequests * 500_000) + " bytes allocated, got " + allocated);

        // Now simulate abandoning all subscriptions - clear references WITHOUT calling cancel()
        // This is what happens when HTTP connections close unexpectedly
        subscriptions.clear();
        latches.clear();

        // Measure memory immediately after abandon (before timeout fires)
        final long immediateAfterAbandon = getDirectMemoryUsed();
        final long immediateLeaked = immediateAfterAbandon - baseline;

        System.out.printf("Direct memory IMMEDIATELY after abandon: %d bytes (%.1f MB)%n",
            immediateAfterAbandon, immediateAfterAbandon / 1024.0 / 1024.0);
        System.out.printf("Leaked memory (before timeout): %d bytes (%.1f MB)%n",
            immediateLeaked, immediateLeaked / 1024.0 / 1024.0);

        // At this point, buffers should still be allocated (timeout hasn't fired)
        // This is expected - the fix uses TIMEOUT-based cleanup, not immediate cleanup
        System.out.printf("Waiting %d seconds for inactivity timeout to fire...%n",
            TEST_TIMEOUT_SECONDS + 1);

        // Wait for timeout to fire (timeout + 1 second buffer)
        Thread.sleep((TEST_TIMEOUT_SECONDS + 1) * 1000);

        // Measure memory after timeout should have fired
        final long afterTimeout = getDirectMemoryUsed();
        final long leakedAfterTimeout = afterTimeout - baseline;

        System.out.printf("Direct memory after timeout: %d bytes (%.1f MB)%n",
            afterTimeout, afterTimeout / 1024.0 / 1024.0);
        System.out.printf("Leaked memory (after timeout): %d bytes (%.1f MB)%n",
            leakedAfterTimeout, leakedAfterTimeout / 1024.0 / 1024.0);

        // The fix: With timeout-based cleanup, buffers should be released after timeout fires
        // Without the fix: Buffers would only be cleaned when GC runs (non-deterministic)
        //
        // PRIMARY ASSERTION: Memory should be cleaned after timeout fires
        // We allow 5MB tolerance for timing variations
        assertTrue(leakedAfterTimeout < 5_000_000,
            String.format("Direct buffer leak detected after timeout! %d bytes (%.1f MB) still allocated " +
                "after %d second inactivity timeout. Timeout-based cleanup should have released buffers.",
                leakedAfterTimeout, leakedAfterTimeout / 1024.0 / 1024.0, TEST_TIMEOUT_SECONDS));
    }

    /**
     * Test that properly consumed requests don't leak.
     *
     * <p>When requests complete normally (body fully consumed),
     * onComplete() is called and buffers are properly cleaned.</p>
     */
    @Test
    @Timeout(60)
    void properlyConsumedRequestsDoNotLeak() throws Exception {
        final FileSystemArtifactSlice slice = new FileSystemArtifactSlice(storage);
        final int numRequests = 20;

        // Record baseline
        final long baseline = getDirectMemoryUsed();

        // Create and fully consume requests
        for (int i = 0; i < numRequests; i++) {
            Response response = slice.response(
                RequestLine.from("GET /test-artifact.jar HTTP/1.1"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);

            // Fully consume body - this triggers onComplete()
            byte[] body = response.body().asBytesFuture().get(10, TimeUnit.SECONDS);
            assertEquals(3 * 1024 * 1024, body.length, "Should receive full file");
        }

        // Force GC
        System.gc();
        Thread.sleep(500);

        // Measure final memory
        final long afterConsume = getDirectMemoryUsed();
        final long delta = afterConsume - baseline;

        System.out.printf("Memory delta after consuming %d requests: %d bytes%n",
            numRequests, delta);

        // Should have minimal leak when properly consumed
        assertTrue(delta < 5_000_000,
            "Unexpected memory growth of " + delta + " bytes after proper consumption");
    }

    /**
     * Test that cancelled subscriptions properly release buffers.
     */
    @Test
    @Timeout(60)
    void cancelledSubscriptionsReleaseBuffers() throws Exception {
        final FileSystemArtifactSlice slice = new FileSystemArtifactSlice(storage);
        final int numRequests = 20;

        // Record baseline
        final long baseline = getDirectMemoryUsed();

        // Create requests and explicitly cancel them
        for (int i = 0; i < numRequests; i++) {
            Response response = slice.response(
                RequestLine.from("GET /test-artifact.jar HTTP/1.1"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);

            // Cancel the body subscription
            CompletableFuture<byte[]> bodyFuture = response.body().asBytesFuture();
            bodyFuture.cancel(true);
        }

        // Force GC
        System.gc();
        Thread.sleep(500);

        // Measure final memory
        final long afterCancel = getDirectMemoryUsed();
        final long delta = afterCancel - baseline;

        System.out.printf("Memory delta after cancelling %d requests: %d bytes%n",
            numRequests, delta);

        // Should have minimal leak when properly cancelled
        assertTrue(delta < 5_000_000,
            "Unexpected memory growth of " + delta + " bytes after cancellation");
    }

    /**
     * Test that HEAD requests don't allocate direct buffers.
     *
     * <p>HEAD requests only need metadata (Content-Length), not the actual
     * file content. This optimization is critical for Maven/Gradle clients
     * that do mass HEAD checks for artifact existence.</p>
     *
     * <p>This test verifies the fix for the January 22, 2026 OOM incident
     * where 200 HEAD requests (22-39 req/s) were allocating buffers that
     * leaked when connections closed.</p>
     */
    @Test
    @Timeout(60)
    void headRequestsDoNotAllocateBuffers() throws Exception {
        final FileSystemArtifactSlice slice = new FileSystemArtifactSlice(storage);
        final int numRequests = 100;

        // Record baseline direct memory
        final long baseline = getDirectMemoryUsed();
        System.out.printf("HEAD test - Direct memory baseline: %d bytes%n", baseline);

        // Send 100 HEAD requests for the existing file
        for (int i = 0; i < numRequests; i++) {
            Response response = slice.response(
                RequestLine.from("HEAD /test-artifact.jar HTTP/1.1"),
                Headers.EMPTY,
                Content.EMPTY
            ).get(5, TimeUnit.SECONDS);

            // Verify HEAD returns 200 with Content-Length
            assertEquals(200, response.status().code(),
                "HEAD for existing file should return 200");

            // Verify Content-Length header is present
            String contentLength = response.headers().stream()
                .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
                .map(h -> h.getValue())
                .findFirst()
                .orElse(null);
            assertNotNull(contentLength, "HEAD response should have Content-Length");
            assertEquals(String.valueOf(3 * 1024 * 1024), contentLength,
                "Content-Length should match file size");

            // Verify body is empty (no content for HEAD)
            byte[] body = response.body().asBytes();
            assertEquals(0, body.length, "HEAD response should have empty body");
        }

        // Measure memory after all HEAD requests
        final long afterHead = getDirectMemoryUsed();
        final long allocated = afterHead - baseline;

        System.out.printf("HEAD test - Direct memory after %d HEAD requests: %d bytes%n",
            numRequests, afterHead);
        System.out.printf("HEAD test - Memory delta: %d bytes (%.2f KB)%n",
            allocated, allocated / 1024.0);

        // HEAD requests should NOT allocate significant direct memory
        // Allow 1MB tolerance for any JVM/Netty overhead
        assertTrue(allocated < 1_000_000,
            String.format("HEAD requests should not allocate direct buffers! " +
                "Allocated %d bytes (%.2f MB) for %d HEAD requests. " +
                "Expected near-zero allocation.",
                allocated, allocated / 1024.0 / 1024.0, numRequests));
    }

    /**
     * Test that HEAD requests for non-existent files return 404.
     */
    @Test
    @Timeout(60)
    void headRequestsReturn404ForMissingFiles() throws Exception {
        final FileSystemArtifactSlice slice = new FileSystemArtifactSlice(storage);

        Response response = slice.response(
            RequestLine.from("HEAD /non-existent-artifact.jar HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertEquals(404, response.status().code(),
            "HEAD for missing file should return 404");
    }

    /**
     * Get current direct buffer memory usage from JMX.
     */
    private static long getDirectMemoryUsed() {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
            .stream()
            .filter(b -> "direct".equals(b.getName()))
            .findFirst()
            .map(BufferPoolMXBean::getMemoryUsed)
            .orElse(0L);
    }
}
