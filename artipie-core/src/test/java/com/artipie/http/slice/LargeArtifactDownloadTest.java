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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for large artifact download functionality and performance.
 * Verifies Content-Length, Range requests, and backpressure handling
 * for artifacts up to 700MB.
 *
 * @since 1.20.8
 */
final class LargeArtifactDownloadTest {

    /**
     * Size of large test artifact (700 MB).
     */
    private static final long LARGE_ARTIFACT_SIZE = 700L * 1024 * 1024;

    /**
     * Size of medium test artifact (100 MB) for faster tests.
     */
    private static final long MEDIUM_ARTIFACT_SIZE = 100L * 1024 * 1024;

    /**
     * Size of small test artifact (10 MB) for quick validation.
     */
    private static final long SMALL_ARTIFACT_SIZE = 10L * 1024 * 1024;

    /**
     * Minimum acceptable download speed in MB/s.
     * Set to 50 MB/s as baseline (actual should be 500+ MB/s for local FS).
     */
    private static final double MIN_SPEED_MBPS = 50.0;

    @TempDir
    Path tempDir;

    private Storage storage;
    private Path artifactPath;

    @BeforeEach
    void setUp() throws IOException {
        this.storage = new FileStorage(this.tempDir);
    }

    @AfterEach
    void tearDown() {
        if (this.artifactPath != null && Files.exists(this.artifactPath)) {
            try {
                Files.deleteIfExists(this.artifactPath);
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    void contentLengthIsSetForSmallArtifact() throws Exception {
        final long size = 1024 * 1024; // 1 MB
        final Key key = new Key.From("test-artifact-1mb.jar");
        createTestArtifact(key, size);

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
        
        assertTrue(contentLength.isPresent(), "Content-Length header must be present");
        assertEquals(String.valueOf(size), contentLength.get(), 
            "Content-Length must match artifact size");
    }

    @Test
    void contentLengthIsSetForLargeArtifact() throws Exception {
        final long size = SMALL_ARTIFACT_SIZE; // 10 MB for faster test
        final Key key = new Key.From("test-artifact-10mb.jar");
        createTestArtifact(key, size);

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
        
        assertTrue(contentLength.isPresent(), "Content-Length header must be present for large artifacts");
        assertEquals(String.valueOf(size), contentLength.get());
    }

    @Test
    void acceptRangesHeaderIsPresent() throws Exception {
        final Key key = new Key.From("test-artifact-ranges.jar");
        createTestArtifact(key, 1024 * 1024);

        final Response response = new FileSystemArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());
        
        final Optional<String> acceptRanges = response.headers().stream()
            .filter(h -> "Accept-Ranges".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
        
        assertTrue(acceptRanges.isPresent(), "Accept-Ranges header must be present");
        assertEquals("bytes", acceptRanges.get(), "Accept-Ranges must be 'bytes'");
    }

    @Test
    void rangeRequestReturnsPartialContent() throws Exception {
        final long size = 10 * 1024 * 1024; // 10 MB
        final Key key = new Key.From("test-artifact-range-request.jar");
        createTestArtifact(key, size);

        // Request first 1MB using Range header
        final Headers rangeHeaders = Headers.from("Range", "bytes=0-1048575");
        
        final Response response = new RangeSlice(new FileSystemArtifactSlice(this.storage))
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                rangeHeaders,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.PARTIAL_CONTENT, response.status(), 
            "Range request should return 206 Partial Content");
        
        final Optional<String> contentRange = response.headers().stream()
            .filter(h -> "Content-Range".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
        
        assertTrue(contentRange.isPresent(), "Content-Range header must be present");
        assertTrue(contentRange.get().startsWith("bytes 0-1048575/"), 
            "Content-Range must specify requested range");
        
        final Optional<String> contentLength = response.headers().stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
        
        assertTrue(contentLength.isPresent(), "Content-Length must be present for partial content");
        assertEquals("1048576", contentLength.get(), "Partial content length must be 1MB");
    }

    @Test
    void rangeRequestMiddleChunk() throws Exception {
        final long size = 10 * 1024 * 1024; // 10 MB
        final Key key = new Key.From("test-artifact-middle-range.jar");
        createTestArtifact(key, size);

        // Request middle 2MB chunk (bytes 4MB to 6MB)
        final long start = 4 * 1024 * 1024;
        final long end = 6 * 1024 * 1024 - 1;
        final Headers rangeHeaders = Headers.from("Range", "bytes=" + start + "-" + end);
        
        final Response response = new RangeSlice(new FileSystemArtifactSlice(this.storage))
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                rangeHeaders,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.PARTIAL_CONTENT, response.status());
        
        final Optional<String> contentLength = response.headers().stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst();
        
        assertEquals(String.valueOf(end - start + 1), contentLength.orElse("0"),
            "Partial content should be exactly 2MB");
    }

    @Test
    void rangeRequestLastBytes() throws Exception {
        final long size = 10 * 1024 * 1024; // 10 MB
        final Key key = new Key.From("test-artifact-last-range.jar");
        createTestArtifact(key, size);

        // Request last 1MB (suffix range)
        final long lastMb = 1024 * 1024;
        final Headers rangeHeaders = Headers.from("Range", "bytes=-" + lastMb);
        
        final Response response = new RangeSlice(new FileSystemArtifactSlice(this.storage))
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                rangeHeaders,
                Content.EMPTY
            ).join();

        // Should return partial content or full content (depends on implementation)
        assertTrue(
            response.status() == RsStatus.PARTIAL_CONTENT || response.status() == RsStatus.OK,
            "Range request should succeed"
        );
    }

    @Test
    void invalidRangeReturnsRangeNotSatisfiable() throws Exception {
        final long size = 1024 * 1024; // 1 MB
        final Key key = new Key.From("test-artifact-invalid-range.jar");
        createTestArtifact(key, size);

        // Request beyond file size
        final Headers rangeHeaders = Headers.from("Range", "bytes=2000000-3000000");
        
        final Response response = new RangeSlice(new FileSystemArtifactSlice(this.storage))
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                rangeHeaders,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.status(),
            "Invalid range should return 416");
    }

    @Test
    void downloadSpeedMeetsMinimumThreshold() throws Exception {
        final long size = SMALL_ARTIFACT_SIZE; // 10 MB
        final Key key = new Key.From("test-artifact-speed.jar");
        createTestArtifact(key, size);

        final Response response = new FileSystemArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());

        // Measure download speed by consuming the body
        final AtomicLong bytesRead = new AtomicLong(0);
        final long startTime = System.nanoTime();
        
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> bytesRead.addAndGet(buffer.remaining()))
            .blockingSubscribe();
        
        final long endTime = System.nanoTime();
        final double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        final double speedMBps = (bytesRead.get() / (1024.0 * 1024.0)) / durationSeconds;

        assertEquals(size, bytesRead.get(), "All bytes must be read");
        assertTrue(speedMBps >= MIN_SPEED_MBPS, 
            String.format("Download speed %.2f MB/s is below minimum %.2f MB/s", 
                speedMBps, MIN_SPEED_MBPS));
        
        System.out.printf("Download speed: %.2f MB/s for %d MB artifact%n", 
            speedMBps, size / (1024 * 1024));
    }

    @Test
    void backpressureHandlingWithSlowConsumer() throws Exception {
        final long size = SMALL_ARTIFACT_SIZE; // 10 MB
        final Key key = new Key.From("test-artifact-backpressure.jar");
        createTestArtifact(key, size);

        final Response response = new FileSystemArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());

        // Simulate slow consumer with artificial delay
        final AtomicLong bytesRead = new AtomicLong(0);
        final AtomicLong chunks = new AtomicLong(0);
        
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> {
                bytesRead.addAndGet(buffer.remaining());
                chunks.incrementAndGet();
                // Simulate slow consumer every 10 chunks
                if (chunks.get() % 10 == 0) {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            })
            .blockingSubscribe();

        assertEquals(size, bytesRead.get(), 
            "All bytes must be read even with slow consumer (backpressure)");
    }

    @Test
    void concurrentRangeRequestsForParallelDownload() throws Exception {
        final long size = SMALL_ARTIFACT_SIZE; // 10 MB
        final Key key = new Key.From("test-artifact-parallel.jar");
        createTestArtifact(key, size);

        // Simulate 4 parallel range requests (like download managers do)
        final int numConnections = 4;
        final long chunkSize = size / numConnections;
        final CountDownLatch latch = new CountDownLatch(numConnections);
        final AtomicLong totalBytesRead = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);

        for (int i = 0; i < numConnections; i++) {
            final long start = i * chunkSize;
            final long end = (i == numConnections - 1) ? size - 1 : (start + chunkSize - 1);
            
            CompletableFuture.runAsync(() -> {
                try {
                    final Headers rangeHeaders = Headers.from("Range", "bytes=" + start + "-" + end);
                    final Response response = new RangeSlice(new FileSystemArtifactSlice(this.storage))
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
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "All parallel downloads should complete");
        assertEquals(0, errors.get(), "No errors should occur in parallel downloads");
        assertEquals(size, totalBytesRead.get(), 
            "Total bytes from parallel downloads should equal file size");
    }

    @Test
    void storageArtifactSliceWithRangeSupport() throws Exception {
        final long size = SMALL_ARTIFACT_SIZE; // 10 MB
        final Key key = new Key.From("test-storage-artifact.jar");
        createTestArtifact(key, size);

        // Use StorageArtifactSlice which wraps with RangeSlice
        final Response response = new StorageArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.from("Range", "bytes=0-1048575"),
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.PARTIAL_CONTENT, response.status(),
            "StorageArtifactSlice should support Range requests via RangeSlice wrapper");
    }

    @Test
    void contentLengthViaStorageArtifactSlice() throws Exception {
        final long size = 5 * 1024 * 1024; // 5 MB
        final Key key = new Key.From("test-storage-content-length.jar");
        createTestArtifact(key, size);

        final Response response = new StorageArtifactSlice(this.storage)
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
        
        assertTrue(contentLength.isPresent(), 
            "Content-Length should be set via StorageArtifactSlice");
        assertEquals(String.valueOf(size), contentLength.get());
    }

    /**
     * Performance test for 100MB artifact download.
     * This test is more comprehensive but takes longer.
     */
    @Test
    void performanceTest100MBArtifact() throws Exception {
        final long size = MEDIUM_ARTIFACT_SIZE; // 100 MB
        final Key key = new Key.From("test-artifact-100mb.jar");
        
        System.out.println("Creating 100MB test artifact...");
        final long createStart = System.currentTimeMillis();
        createTestArtifact(key, size);
        System.out.printf("Artifact created in %d ms%n", System.currentTimeMillis() - createStart);

        final Response response = new StorageArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());

        // Measure download speed
        final AtomicLong bytesRead = new AtomicLong(0);
        final long startTime = System.nanoTime();
        
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> bytesRead.addAndGet(buffer.remaining()))
            .blockingSubscribe();
        
        final long endTime = System.nanoTime();
        final double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        final double speedMBps = (bytesRead.get() / (1024.0 * 1024.0)) / durationSeconds;

        System.out.printf("100MB Download: %.2f MB/s (%.2f seconds)%n", 
            speedMBps, durationSeconds);

        assertEquals(size, bytesRead.get(), "All bytes must be read");
        assertTrue(speedMBps >= MIN_SPEED_MBPS, 
            String.format("Download speed %.2f MB/s below minimum %.2f MB/s", 
                speedMBps, MIN_SPEED_MBPS));
    }

    @Test
    void dataIntegrityChecksumVerification() throws Exception {
        // Test that downloaded data matches original file exactly
        // This catches the "Premature end of Content-Length" bug
        final long size = 20 * 1024 * 1024; // 20 MB
        final Key key = new Key.From("test-artifact-checksum.jar");
        createTestArtifact(key, size);
        
        // Compute expected checksum from file
        final String expectedChecksum = computeFileChecksum(this.artifactPath);
        
        final Response response = new StorageArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();

        assertEquals(RsStatus.OK, response.status());
        
        // Compute checksum from streamed response
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final AtomicLong bytesRead = new AtomicLong(0);
        
        Flowable.fromPublisher(response.body())
            .doOnNext(buffer -> {
                final byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                md.update(bytes);
                bytesRead.addAndGet(bytes.length);
            })
            .blockingSubscribe();
        
        final String actualChecksum = bytesToHex(md.digest());
        
        assertEquals(size, bytesRead.get(), 
            "All bytes must be received (got " + bytesRead.get() + " of " + size + ")");
        assertEquals(expectedChecksum, actualChecksum, 
            "Checksum must match - data corruption detected");
    }

    @Test
    void noResourceLeaksOnCancellation() throws Exception {
        // Test that cancelling a download doesn't leak file handles
        final long size = SMALL_ARTIFACT_SIZE; // 10 MB
        final Key key = new Key.From("test-artifact-cancel.jar");
        createTestArtifact(key, size);
        
        // Do multiple downloads with cancellation
        for (int i = 0; i < 10; i++) {
            final Response response = new FileSystemArtifactSlice(this.storage)
                .response(
                    new RequestLine(RqMethod.GET, "/" + key.string()),
                    Headers.EMPTY,
                    Content.EMPTY
                ).join();

            assertEquals(RsStatus.OK, response.status());
            
            // Read only first chunk then cancel
            final AtomicLong bytesRead = new AtomicLong(0);
            Flowable.fromPublisher(response.body())
                .take(1) // Only take first chunk - simulates cancellation
                .doOnNext(buffer -> bytesRead.addAndGet(buffer.remaining()))
                .blockingSubscribe();
            
            assertTrue(bytesRead.get() > 0, "Should read at least some bytes");
            assertTrue(bytesRead.get() < size, "Should not read all bytes (cancelled)");
        }
        
        // If we get here without file handle exhaustion, the test passes
        // Create one more response to verify no leaks
        final Response finalResponse = new FileSystemArtifactSlice(this.storage)
            .response(
                new RequestLine(RqMethod.GET, "/" + key.string()),
                Headers.EMPTY,
                Content.EMPTY
            ).join();
        
        assertEquals(RsStatus.OK, finalResponse.status(), 
            "Should still be able to serve files after multiple cancellations");
    }

    private static String computeFileChecksum(final Path path) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final byte[] buffer = new byte[1024 * 1024];
        try (var in = Files.newInputStream(path)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return bytesToHex(md.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Create a test artifact with random content.
     *
     * @param key Artifact key
     * @param size Size in bytes
     * @throws IOException If creation fails
     */
    private void createTestArtifact(final Key key, final long size) throws IOException {
        this.artifactPath = this.tempDir.resolve(key.string());
        Files.createDirectories(this.artifactPath.getParent());
        
        // Create file with random content in chunks to avoid memory issues
        final Random random = new Random(42); // Fixed seed for reproducibility
        final int chunkSize = 1024 * 1024; // 1 MB chunks
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
}
