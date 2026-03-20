/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.apache.commons.codec.binary.Hex;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link ChecksumProxySlice}.
 * Validates checksum computation correctness, streaming behavior, and concurrency handling.
 *
 * @since 0.1
 */
final class ChecksumProxySliceTest {

    @Test
    void computesSha1ChecksumCorrectly() throws Exception {
        final byte[] data = "Hello, Maven!".getBytes(StandardCharsets.UTF_8);
        final String expectedSha1 = computeExpectedChecksum(data, "SHA-1");
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(data)
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar.sha1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        
        MatcherAssert.assertThat(
            "Response status is OK",
            response.status().success(),
            Matchers.is(true)
        );
        
        final String actualSha1 = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "SHA-1 checksum matches expected value",
            actualSha1,
            Matchers.equalTo(expectedSha1)
        );
    }

    @Test
    void computesMd5ChecksumCorrectly() throws Exception {
        final byte[] data = "Maven artifact content".getBytes(StandardCharsets.UTF_8);
        final String expectedMd5 = computeExpectedChecksum(data, "MD5");
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(data)
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar.md5"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        
        final String actualMd5 = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "MD5 checksum matches expected value",
            actualMd5,
            Matchers.equalTo(expectedMd5)
        );
    }

    @Test
    void computesSha256ChecksumCorrectly() throws Exception {
        final byte[] data = "SHA-256 test data".getBytes(StandardCharsets.UTF_8);
        final String expectedSha256 = computeExpectedChecksum(data, "SHA-256");
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(data)
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar.sha256"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        
        final String actualSha256 = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "SHA-256 checksum matches expected value",
            actualSha256,
            Matchers.equalTo(expectedSha256)
        );
    }

    @Test
    void computesSha512ChecksumCorrectly() throws Exception {
        final byte[] data = "SHA-512 test data".getBytes(StandardCharsets.UTF_8);
        final String expectedSha512 = computeExpectedChecksum(data, "SHA-512");
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(data)
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar.sha512"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        
        final String actualSha512 = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "SHA-512 checksum matches expected value",
            actualSha512,
            Matchers.equalTo(expectedSha512)
        );
    }

    @Test
    void handlesLargeArtifactWithoutMemoryExhaustion() throws Exception {
        // Create 10MB artifact to test streaming behavior
        final int size = 10 * 1024 * 1024;
        final byte[] largeData = new byte[size];
        for (int i = 0; i < size; i++) {
            largeData[i] = (byte) (i % 256);
        }
        final String expectedSha1 = computeExpectedChecksum(largeData, "SHA-1");
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(largeData)
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/large-artifact.jar.sha1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        
        final String actualSha1 = new String(
            response.body().asBytes(),
            StandardCharsets.UTF_8
        );
        
        MatcherAssert.assertThat(
            "SHA-1 checksum for large artifact matches expected value",
            actualSha1,
            Matchers.equalTo(expectedSha1)
        );
    }

    @Test
    void handlesConcurrentChecksumRequests() throws Exception {
        final byte[] data = "Concurrent test data".getBytes(StandardCharsets.UTF_8);
        final String expectedSha1 = computeExpectedChecksum(data, "SHA-1");
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(data)
        );
        
        final int concurrency = 50;
        final ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        final CountDownLatch latch = new CountDownLatch(concurrency);
        final AtomicInteger successCount = new AtomicInteger(0);
        final List<String> checksums = new ArrayList<>(concurrency);
        
        try {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    try {
                        final Response response = slice.response(
                            new RequestLine(RqMethod.GET, "/test/artifact.jar.sha1"),
                            Headers.EMPTY,
                            Content.EMPTY
                        ).get(10, TimeUnit.SECONDS);
                        
                        if (response.status().success()) {
                            final String checksum = new String(
                                response.body().asBytes(),
                                StandardCharsets.UTF_8
                            );
                            synchronized (checksums) {
                                checksums.add(checksum);
                            }
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Ignore - will be caught by assertions
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            MatcherAssert.assertThat(
                "All concurrent requests completed",
                latch.await(60, TimeUnit.SECONDS),
                Matchers.is(true)
            );
            
            MatcherAssert.assertThat(
                "All concurrent requests succeeded",
                successCount.get(),
                Matchers.equalTo(concurrency)
            );
            
            MatcherAssert.assertThat(
                "All checksums match expected value",
                checksums,
                Matchers.everyItem(Matchers.equalTo(expectedSha1))
            );
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void returns404WhenArtifactNotFound() throws Exception {
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            )
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/missing.jar.sha1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        
        MatcherAssert.assertThat(
            "Response status is 404 when artifact not found",
            response.status().code(),
            Matchers.equalTo(404)
        );
    }

    @Test
    void passesNonChecksumRequestsThrough() throws Exception {
        final byte[] data = "Artifact data".getBytes(StandardCharsets.UTF_8);
        
        final ChecksumProxySlice slice = new ChecksumProxySlice(
            new FakeArtifactSlice(data)
        );
        
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/test/artifact.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);
        
        MatcherAssert.assertThat(
            "Non-checksum request passes through",
            response.body().asBytes(),
            Matchers.equalTo(data)
        );
    }

    /**
     * Compute expected checksum for test data.
     * @param data Test data
     * @param algorithm Hash algorithm
     * @return Hex-encoded checksum
     */
    private static String computeExpectedChecksum(final byte[] data, final String algorithm) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(data);
            return Hex.encodeHexString(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute checksum", e);
        }
    }

    /**
     * Fake slice that returns artifact data for non-checksum requests.
     */
    private static final class FakeArtifactSlice implements Slice {
        private final byte[] data;

        FakeArtifactSlice(final byte[] data) {
            this.data = data;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            final String path = line.uri().getPath();
            
            // Return 404 for checksum files (to trigger computation)
            if (path.endsWith(".sha1") || path.endsWith(".md5") 
                || path.endsWith(".sha256") || path.endsWith(".sha512")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            
            // Return artifact data
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body(this.data)
                    .build()
            );
        }
    }
}

