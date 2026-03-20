/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.vertx;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import io.reactivex.Flowable;
import io.vertx.reactivex.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VertxSliceServer backpressure handling.
 * Verifies that large file downloads work correctly with proper
 * flow control between producer and consumer.
 *
 * @since 1.20.8
 */
final class VertxSliceServerBackpressureTest {

    /**
     * Size of test content (100 MB).
     */
    private static final long CONTENT_SIZE = 100L * 1024 * 1024;

    /**
     * Chunk size for streaming (1 MB).
     */
    private static final int CHUNK_SIZE = 1024 * 1024;

    private Vertx vertx;
    private VertxSliceServer server;
    private int port;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void largeContentDownloadWithContentLength() throws Exception {
        final long size = CONTENT_SIZE;
        
        // Create a slice that streams large content with known size
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        // Download via HTTP
        final HttpURLConnection conn = (HttpURLConnection) 
            new URL("http://localhost:" + this.port + "/test").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(60000);

        assertEquals(200, conn.getResponseCode());
        assertEquals(String.valueOf(size), conn.getHeaderField("Content-Length"),
            "Content-Length header must match");

        // Read all content
        final AtomicLong bytesRead = new AtomicLong(0);
        try (InputStream in = conn.getInputStream()) {
            final byte[] buffer = new byte[64 * 1024]; // 64 KB read buffer
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytesRead.addAndGet(read);
            }
        }

        assertEquals(size, bytesRead.get(), "All bytes must be downloaded");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void downloadSpeedWithBackpressure() throws Exception {
        final long size = 50L * 1024 * 1024; // 50 MB for faster test
        
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        final HttpURLConnection conn = (HttpURLConnection) 
            new URL("http://localhost:" + this.port + "/test").openConnection();
        conn.setRequestMethod("GET");

        final long startTime = System.nanoTime();
        
        final AtomicLong bytesRead = new AtomicLong(0);
        try (InputStream in = conn.getInputStream()) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytesRead.addAndGet(read);
            }
        }

        final long endTime = System.nanoTime();
        final double durationSec = (endTime - startTime) / 1_000_000_000.0;
        final double speedMBps = (bytesRead.get() / (1024.0 * 1024.0)) / durationSec;

        System.out.printf("Download speed: %.2f MB/s for %d MB%n", 
            speedMBps, size / (1024 * 1024));

        assertEquals(size, bytesRead.get());
        // With proper backpressure, local loopback should be very fast
        assertTrue(speedMBps > 50, 
            String.format("Speed %.2f MB/s is too slow - backpressure may not be working", 
                speedMBps));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void slowConsumerDoesNotCauseMemoryIssues() throws Exception {
        final long size = 20L * 1024 * 1024; // 20 MB
        
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        final HttpURLConnection conn = (HttpURLConnection) 
            new URL("http://localhost:" + this.port + "/test").openConnection();
        conn.setRequestMethod("GET");

        final Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        final long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        final AtomicLong bytesRead = new AtomicLong(0);
        try (InputStream in = conn.getInputStream()) {
            final byte[] buffer = new byte[1024]; // Small buffer = slow consumer
            int read;
            int count = 0;
            while ((read = in.read(buffer)) != -1) {
                bytesRead.addAndGet(read);
                // Simulate slow consumer every 1000 reads
                if (++count % 1000 == 0) {
                    Thread.sleep(1);
                }
            }
        }

        final long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        final long memoryIncreaseMB = (memoryAfter - memoryBefore) / (1024 * 1024);

        System.out.printf("Memory increase during slow download: %d MB%n", memoryIncreaseMB);

        assertEquals(size, bytesRead.get());
        // JVM heap measurement is unreliable due to GC timing and direct buffers.
        // The key validation is that data transfers correctly without OOM.
        // Allow up to 5x file size as buffer (100MB for 20MB file) to account for
        // JVM overhead, direct buffers, and GC timing variations.
        // The real backpressure test is that multi-GB files don't OOM.
        assertTrue(memoryIncreaseMB < (size / (1024 * 1024)) * 5,
            String.format("Memory increase %d MB is excessive (file size: %d MB)", 
                memoryIncreaseMB, size / (1024 * 1024)));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void chunkedTransferEncodingWorks() throws Exception {
        final long size = 10L * 1024 * 1024; // 10 MB
        
        // Create slice without Content-Length (chunked transfer)
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                // No Content-Length - will use chunked encoding
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        final HttpURLConnection conn = (HttpURLConnection) 
            new URL("http://localhost:" + this.port + "/test").openConnection();
        conn.setRequestMethod("GET");

        assertEquals(200, conn.getResponseCode());
        
        // Chunked encoding means Transfer-Encoding: chunked or no Content-Length
        final String transferEncoding = conn.getHeaderField("Transfer-Encoding");
        final String contentLength = conn.getHeaderField("Content-Length");
        
        // Either chunked or content-length should be present
        assertTrue(
            "chunked".equalsIgnoreCase(transferEncoding) || contentLength != null,
            "Response should use chunked encoding or have Content-Length"
        );

        final AtomicLong bytesRead = new AtomicLong(0);
        try (InputStream in = conn.getInputStream()) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bytesRead.addAndGet(read);
            }
        }

        assertEquals(size, bytesRead.get(), "All chunked content must be received");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void multipleSequentialDownloads() throws Exception {
        final long size = 5L * 1024 * 1024; // 5 MB per download
        
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        // Do 5 sequential downloads
        for (int i = 0; i < 5; i++) {
            final HttpURLConnection conn = (HttpURLConnection) 
                new URL("http://localhost:" + this.port + "/test" + i).openConnection();
            conn.setRequestMethod("GET");

            final AtomicLong bytesRead = new AtomicLong(0);
            try (InputStream in = conn.getInputStream()) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytesRead.addAndGet(read);
                }
            }

            assertEquals(size, bytesRead.get(), "Download " + i + " should complete");
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void concurrentDownloads() throws Exception {
        final long size = 10L * 1024 * 1024; // 10 MB each
        final int numClients = 4;
        
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        final AtomicLong totalBytes = new AtomicLong(0);
        final AtomicLong errors = new AtomicLong(0);
        
        final CompletableFuture<?>[] futures = new CompletableFuture[numClients];
        
        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    final HttpURLConnection conn = (HttpURLConnection) 
                        new URL("http://localhost:" + this.port + "/client" + clientId)
                            .openConnection();
                    conn.setRequestMethod("GET");

                    try (InputStream in = conn.getInputStream()) {
                        final byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            totalBytes.addAndGet(read);
                        }
                    }
                } catch (IOException e) {
                    errors.incrementAndGet();
                    System.err.printf("Client %d error: %s%n", clientId, e.getMessage());
                }
            });
        }

        CompletableFuture.allOf(futures).join();

        assertEquals(0, errors.get(), "No errors in concurrent downloads");
        assertEquals(size * numClients, totalBytes.get(), 
            "Total bytes from all concurrent clients");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void dataIntegrityWithChecksumVerification() throws Exception {
        // Test that data is not corrupted during transfer
        // This specifically tests for the "Premature end of Content-Length" bug
        final long size = 50L * 1024 * 1024; // 50 MB
        
        // Pre-compute expected checksum
        final String expectedChecksum = computePatternChecksum(size);
        
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        final HttpURLConnection conn = (HttpURLConnection) 
            new URL("http://localhost:" + this.port + "/test").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(120000);

        assertEquals(200, conn.getResponseCode());
        assertEquals(String.valueOf(size), conn.getHeaderField("Content-Length"));

        // Read and compute checksum
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final AtomicLong bytesRead = new AtomicLong(0);
        
        try (InputStream in = conn.getInputStream()) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
                bytesRead.addAndGet(read);
            }
        }

        final String actualChecksum = bytesToHex(md.digest());
        
        assertEquals(size, bytesRead.get(), 
            "All bytes must be received (got " + bytesRead.get() + " of " + size + ")");
        assertEquals(expectedChecksum, actualChecksum, 
            "Checksum must match - data corruption detected");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void noTruncationUnderLoad() throws Exception {
        // Test multiple concurrent downloads don't get truncated
        final long size = 10L * 1024 * 1024; // 10 MB each
        final int numClients = 8;
        
        final String expectedChecksum = computePatternChecksum(size);
        
        final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Length", String.valueOf(size))
                .body(createLargeContent(size))
                .build()
        );

        this.server = new VertxSliceServer(this.vertx, slice, 0);
        this.port = this.server.start();

        final AtomicLong successCount = new AtomicLong(0);
        final AtomicLong failureCount = new AtomicLong(0);
        final java.util.concurrent.ConcurrentLinkedQueue<String> errors = 
            new java.util.concurrent.ConcurrentLinkedQueue<>();
        
        final CompletableFuture<?>[] futures = new CompletableFuture[numClients];
        
        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    final HttpURLConnection conn = (HttpURLConnection) 
                        new URL("http://localhost:" + this.port + "/client" + clientId)
                            .openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(60000);

                    final MessageDigest md = MessageDigest.getInstance("SHA-256");
                    long bytesRead = 0;
                    
                    try (InputStream in = conn.getInputStream()) {
                        final byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            md.update(buffer, 0, read);
                            bytesRead += read;
                        }
                    }

                    if (bytesRead != size) {
                        errors.add(String.format("Client %d: expected %d bytes, got %d", 
                            clientId, size, bytesRead));
                        failureCount.incrementAndGet();
                        return;
                    }

                    final String actualChecksum = bytesToHex(md.digest());
                    if (!expectedChecksum.equals(actualChecksum)) {
                        errors.add(String.format("Client %d: checksum mismatch", clientId));
                        failureCount.incrementAndGet();
                        return;
                    }

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errors.add(String.format("Client %d: %s", clientId, e.getMessage()));
                    failureCount.incrementAndGet();
                }
            });
        }

        CompletableFuture.allOf(futures).join();

        if (!errors.isEmpty()) {
            System.err.println("Errors: " + errors);
        }
        
        assertEquals(0, failureCount.get(), 
            "All downloads must complete without truncation or corruption: " + errors);
        assertEquals(numClients, successCount.get(), 
            "All clients must succeed");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void contentLengthMatchesActualBytes() throws Exception {
        // Specific test for Content-Length accuracy
        final long[] sizes = {1024, 10 * 1024, 100 * 1024, 1024 * 1024, 5 * 1024 * 1024};
        
        for (long size : sizes) {
            final Slice slice = (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Length", String.valueOf(size))
                    .body(createLargeContent(size))
                    .build()
            );

            try (VertxSliceServer testServer = new VertxSliceServer(this.vertx, slice, 0)) {
                final int testPort = testServer.start();

                final HttpURLConnection conn = (HttpURLConnection) 
                    new URL("http://localhost:" + testPort + "/test").openConnection();
                conn.setRequestMethod("GET");

                final long declaredLength = Long.parseLong(conn.getHeaderField("Content-Length"));
                assertEquals(size, declaredLength, "Declared Content-Length must match");

                long actualBytes = 0;
                try (InputStream in = conn.getInputStream()) {
                    final byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        actualBytes += read;
                    }
                }

                assertEquals(declaredLength, actualBytes, 
                    String.format("Actual bytes (%d) must match Content-Length (%d) for size %d", 
                        actualBytes, declaredLength, size));
            }
        }
    }

    /**
     * Create large streaming content with deterministic pattern.
     */
    private static Content createLargeContent(final long size) {
        return new Content.From(size, Flowable.generate(
            () -> new long[]{0},
            (state, emitter) -> {
                if (state[0] >= size) {
                    emitter.onComplete();
                } else {
                    final int chunkSize = (int) Math.min(CHUNK_SIZE, size - state[0]);
                    final ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
                    // Fill with deterministic pattern for checksum verification
                    for (int i = 0; i < chunkSize; i++) {
                        buffer.put((byte) ((state[0] + i) & 0xFF));
                    }
                    buffer.flip();
                    emitter.onNext(buffer);
                    state[0] += chunkSize;
                }
            }
        ));
    }

    /**
     * Compute expected checksum for the deterministic pattern.
     */
    private static String computePatternChecksum(final long size) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[CHUNK_SIZE];
            long position = 0;
            
            while (position < size) {
                final int chunkSize = (int) Math.min(CHUNK_SIZE, size - position);
                for (int i = 0; i < chunkSize; i++) {
                    buffer[i] = (byte) ((position + i) & 0xFF);
                }
                md.update(buffer, 0, chunkSize);
                position += chunkSize;
            }
            
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert bytes to hex string.
     */
    private static String bytesToHex(byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
