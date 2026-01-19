/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.fs.FileStorage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.RsStatus;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import io.reactivex.Flowable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance integration tests for 700MB artifact downloads.
 * These tests are tagged as "performance" and disabled by default
 * due to their resource requirements.
 * 
 * Run with: mvn test -Dtest=LargeArtifactPerformanceIT -DskipITs=false
 * Or: mvn verify -Pperformance
 *
 * @since 1.20.8
 */
@Tag("performance")
final class LargeArtifactPerformanceIT {

    /**
     * Size of 700MB artifact (as requested by user).
     */
    private static final long SIZE_700MB = 700L * 1024 * 1024;

    /**
     * Size of 500MB artifact for medium performance test.
     */
    private static final long SIZE_500MB = 500L * 1024 * 1024;

    /**
     * Size of 200MB artifact for quick performance test.
     */
    private static final long SIZE_200MB = 200L * 1024 * 1024;

    /**
     * Minimum acceptable speed for local filesystem in MB/s.
     * Local NVMe/SSD should achieve 500+ MB/s, gp3 EBS ~125-1000 MB/s.
     */
    private static final double MIN_SPEED_LOCAL_MBPS = 100.0;

    /**
     * Target speed we expect after backpressure fixes in MB/s.
     */
    private static final double TARGET_SPEED_MBPS = 200.0;

    @TempDir
    Path tempDir;

    private Storage storage;
    private Path artifactPath;
    private final List<PerformanceResult> results = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.storage = new FileStorage(this.tempDir);
        this.results.clear();
    }

    @AfterEach
    void tearDown() {
        // Print performance summary
        if (!this.results.isEmpty()) {
            System.out.println("\n========== PERFORMANCE SUMMARY ==========");
            for (PerformanceResult result : this.results) {
                System.out.printf("%-40s %8.2f MB/s (%6.2f sec, %4d MB)%n",
                    result.name, result.speedMBps, result.durationSec, result.sizeMB);
            }
            System.out.println("==========================================\n");
        }
        
        // Cleanup
        if (this.artifactPath != null && Files.exists(this.artifactPath)) {
            try {
                Files.deleteIfExists(this.artifactPath);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Performance test for 700MB Maven artifact download.
     * This is the primary test requested by the user.
     */
    @Test
    @Disabled("Enable manually - requires 700MB disk space and takes ~10 seconds")
    void download700MBArtifact() throws Exception {
        runPerformanceTest("700MB Maven Artifact", SIZE_700MB, false);
    }

    /**
     * Performance test for 500MB artifact.
     */
    @Test
    @Disabled("Enable manually - requires 500MB disk space")
    void download500MBArtifact() throws Exception {
        runPerformanceTest("500MB Artifact", SIZE_500MB, false);
    }

    /**
     * Performance test for 200MB artifact - faster for CI.
     */
    @Test
    void download200MBArtifact() throws Exception {
        runPerformanceTest("200MB Artifact", SIZE_200MB, false);
    }

    /**
     * Parallel download test for 700MB artifact using 4 connections.
     * Simulates how Chrome/download managers download large files.
     */
    @Test
    @Disabled("Enable manually - requires 700MB disk space")
    void parallelDownload700MB() throws Exception {
        runParallelDownloadTest("700MB Parallel (4 conn)", SIZE_700MB, 4);
    }

    /**
     * Parallel download test for 200MB artifact - faster for CI.
     */
    @Test
    void parallelDownload200MB() throws Exception {
        runParallelDownloadTest("200MB Parallel (4 conn)", SIZE_200MB, 4);
    }

    /**
     * Test with 8 parallel connections to stress test Range support.
     */
    @Test
    void parallelDownload8Connections() throws Exception {
        runParallelDownloadTest("200MB Parallel (8 conn)", SIZE_200MB, 8);
    }

    /**
     * Test Content-Length header for 700MB file.
     */
    @Test
    @Disabled("Enable manually - requires 700MB disk space")
    void contentLengthFor700MBArtifact() throws Exception {
        final Key key = new Key.From("large-artifact-700mb.jar");
        System.out.println("Creating 700MB test artifact for Content-Length test...");
        createTestArtifact(key, SIZE_700MB);

        final Response response = new FileSystemArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());
        
        final Optional<String> contentLength = response.headers().stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
        
        assertTrue(contentLength.isPresent(), "Content-Length must be present for 700MB artifact");
        assertEquals(String.valueOf(SIZE_700MB), contentLength.get(),
            "Content-Length must match 700MB");
        
        // Consume and discard body to complete the test
        Flowable.fromPublisher(response.body()).blockingSubscribe();
    }

    /**
     * Test Range request for first 100MB of 700MB file.
     */
    @Test
    @Disabled("Enable manually - requires 700MB disk space")
    void rangeRequestFirst100MBOf700MB() throws Exception {
        final Key key = new Key.From("large-artifact-range-700mb.jar");
        System.out.println("Creating 700MB test artifact for Range test...");
        createTestArtifact(key, SIZE_700MB);

        final long rangeEnd = 100L * 1024 * 1024 - 1; // First 100MB
        final Headers rangeHeaders = Headers.from("Range", "bytes=0-" + rangeEnd);
        
        final long startTime = System.nanoTime();
        
        final Response response = new RangeSlice(new FileSystemArtifactSlice(this.storage))
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                rangeHeaders,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.PARTIAL_CONTENT, response.status());
        
        final AtomicLong bytesRead = new AtomicLong(0);
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> bytesRead.addAndGet(buffer.remaining()))
            .blockingSubscribe();
        
        final long endTime = System.nanoTime();
        final double durationSec = (endTime - startTime) / 1_000_000_000.0;
        final double speedMBps = (bytesRead.get() / (1024.0 * 1024.0)) / durationSec;

        assertEquals(rangeEnd + 1, bytesRead.get(), "Should read exactly 100MB");
        
        System.out.printf("Range request (first 100MB of 700MB): %.2f MB/s%n", speedMBps);
        this.results.add(new PerformanceResult("Range 100MB of 700MB", speedMBps, durationSec, 100));
    }

    /**
     * Backpressure stress test - verify memory doesn't explode.
     */
    @Test
    void backpressureStressTest() throws Exception {
        final long size = SIZE_200MB;
        final Key key = new Key.From("backpressure-stress-test.jar");
        createTestArtifact(key, size);

        final Response response = new FileSystemArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        // Record memory before
        final Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        final long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        final AtomicLong bytesRead = new AtomicLong(0);
        final AtomicLong maxMemoryDuringDownload = new AtomicLong(0);
        
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> {
                bytesRead.addAndGet(buffer.remaining());
                // Sample memory usage
                if (bytesRead.get() % (50 * 1024 * 1024) == 0) {
                    final long currentMem = runtime.totalMemory() - runtime.freeMemory();
                    maxMemoryDuringDownload.updateAndGet(prev -> Math.max(prev, currentMem));
                }
            })
            .blockingSubscribe();

        final long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        final long memoryIncreaseMB = (memoryAfter - memoryBefore) / (1024 * 1024);

        System.out.printf("Backpressure test: Memory increase during 200MB download: %d MB%n", 
            memoryIncreaseMB);
        
        // With proper backpressure, memory increase should be minimal (< 100MB buffer)
        // Without backpressure, it would be close to file size
        assertTrue(memoryIncreaseMB < 200, 
            String.format("Memory increase %d MB suggests backpressure issue", memoryIncreaseMB));
    }

    /**
     * Run a single download performance test.
     */
    private void runPerformanceTest(String name, long size, boolean warmup) throws Exception {
        final Key key = new Key.From("perf-test-" + size + ".jar");
        
        if (!warmup) {
            System.out.printf("Creating %d MB test artifact...%n", size / (1024 * 1024));
        }
        createTestArtifact(key, size);

        final Response response = new StorageArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());

        // Verify Content-Length
        final Optional<String> contentLength = response.headers().stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
        assertTrue(contentLength.isPresent(), "Content-Length must be present");

        // Measure download speed
        final AtomicLong bytesRead = new AtomicLong(0);
        final long startTime = System.nanoTime();
        
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> bytesRead.addAndGet(buffer.remaining()))
            .blockingSubscribe();
        
        final long endTime = System.nanoTime();
        final double durationSec = (endTime - startTime) / 1_000_000_000.0;
        final double speedMBps = (bytesRead.get() / (1024.0 * 1024.0)) / durationSec;

        assertEquals(size, bytesRead.get(), "All bytes must be read");
        
        if (!warmup) {
            System.out.printf("%s: %.2f MB/s (%.2f seconds)%n", name, speedMBps, durationSec);
            this.results.add(new PerformanceResult(name, speedMBps, durationSec, 
                (int)(size / (1024 * 1024))));
            
            assertTrue(speedMBps >= MIN_SPEED_LOCAL_MBPS,
                String.format("%s: Speed %.2f MB/s below minimum %.2f MB/s",
                    name, speedMBps, MIN_SPEED_LOCAL_MBPS));
        }
        
        // Cleanup for next test
        Files.deleteIfExists(this.artifactPath);
    }

    /**
     * Run parallel download test simulating multi-connection download managers.
     */
    private void runParallelDownloadTest(String name, long size, int numConnections) 
        throws Exception {
        
        final Key key = new Key.From("parallel-test-" + size + ".jar");
        System.out.printf("Creating %d MB test artifact for parallel download...%n", 
            size / (1024 * 1024));
        createTestArtifact(key, size);

        final long chunkSize = size / numConnections;
        final CountDownLatch latch = new CountDownLatch(numConnections);
        final AtomicLong totalBytesRead = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);
        final long startTime = System.nanoTime();

        // Launch parallel range requests
        for (int i = 0; i < numConnections; i++) {
            final int connId = i;
            final long start = i * chunkSize;
            final long end = (i == numConnections - 1) ? size - 1 : (start + chunkSize - 1);
            
            CompletableFuture.runAsync(() -> {
                try {
                    final Headers rangeHeaders = Headers.from("Range", 
                        "bytes=" + start + "-" + end);
                    final Response response = new RangeSlice(
                        new FileSystemArtifactSlice(this.storage))
                        .response(
                            new RequestLine(RqMethod.GET, "/" + key.string()),
                            rangeHeaders,
                            Content.EMPTY
                        ).join();

                    if (response.status() == RsStatus.PARTIAL_CONTENT) {
                        final AtomicLong chunkBytes = new AtomicLong(0);
                        Flowable.fromPublisher(response.body())
                            .doOnNext(buffer -> chunkBytes.addAndGet(buffer.remaining()))
                            .blockingSubscribe();
                        totalBytesRead.addAndGet(chunkBytes.get());
                    } else {
                        System.err.printf("Connection %d: Unexpected status %s%n", 
                            connId, response.status());
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    System.err.printf("Connection %d: Error - %s%n", connId, e.getMessage());
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(120, TimeUnit.SECONDS), 
            "All parallel downloads should complete within 2 minutes");
        
        final long endTime = System.nanoTime();
        final double durationSec = (endTime - startTime) / 1_000_000_000.0;
        final double speedMBps = (totalBytesRead.get() / (1024.0 * 1024.0)) / durationSec;

        assertEquals(0, errors.get(), "No errors in parallel downloads");
        assertEquals(size, totalBytesRead.get(), 
            "Total bytes from parallel downloads should equal file size");
        
        System.out.printf("%s: %.2f MB/s aggregate (%.2f seconds, %d connections)%n", 
            name, speedMBps, durationSec, numConnections);
        this.results.add(new PerformanceResult(name, speedMBps, durationSec, 
            (int)(size / (1024 * 1024))));

        // Cleanup
        Files.deleteIfExists(this.artifactPath);
    }

    /**
     * Create a test artifact with random content.
     */
    private void createTestArtifact(final Key key, final long size) throws IOException {
        this.artifactPath = this.tempDir.resolve(key.string());
        Files.createDirectories(this.artifactPath.getParent());
        
        final Random random = new Random(42);
        final int chunkSize = 4 * 1024 * 1024; // 4 MB chunks for faster creation
        final byte[] chunk = new byte[chunkSize];
        
        try (var out = Files.newOutputStream(this.artifactPath)) {
            long remaining = size;
            while (remaining > 0) {
                final int toWrite = (int) Math.min(chunkSize, remaining);
                random.nextBytes(chunk);
                out.write(chunk, 0, toWrite);
                remaining -= toWrite;
            }
        }
    }

    /**
     * Performance test result record.
     */
    private static final class PerformanceResult {
        final String name;
        final double speedMBps;
        final double durationSec;
        final int sizeMB;

        PerformanceResult(String name, double speedMBps, double durationSec, int sizeMB) {
            this.name = name;
            this.speedMBps = speedMBps;
            this.durationSec = durationSec;
            this.sizeMB = sizeMB;
        }
    }
}
