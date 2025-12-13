/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.vertx;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
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
        // With backpressure, memory increase should be limited
        assertTrue(memoryIncreaseMB < size / (1024 * 1024),
            String.format("Memory increase %d MB suggests buffering without backpressure", 
                memoryIncreaseMB));
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

    /**
     * Create large streaming content.
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
                    // Fill with pattern for verification if needed
                    for (int i = 0; i < chunkSize; i++) {
                        buffer.put((byte) (i & 0xFF));
                    }
                    buffer.flip();
                    emitter.onNext(buffer);
                    state[0] += chunkSize;
                }
            }
        ));
    }
}
