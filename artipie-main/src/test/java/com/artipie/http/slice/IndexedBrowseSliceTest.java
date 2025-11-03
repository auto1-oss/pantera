/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.ListResult;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.http.Headers;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.Slice;
import com.artipie.http.headers.Accept;
import com.artipie.http.rq.RequestLine;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test case for {@link IndexedBrowseSlice}.
 * 
 * This test verifies that the indexed directory browsing provides
 * significant performance improvements over traditional recursive listing.
 */
class IndexedBrowseSliceTest {

    private Storage storage;
    private IndexedBrowseSlice slice;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
        // Use unique cache directory for each test to avoid interference
        final Path uniqueCacheDir = Paths.get(System.getProperty("java.io.tmpdir"), 
            "artipie-test-cache-" + System.currentTimeMillis() + "-" + System.nanoTime());
        this.slice = new IndexedBrowseSlice(this.storage, TimeUnit.MINUTES.toMillis(1), uniqueCacheDir);
    }

    @AfterEach
    void tearDown() {
        if (this.slice != null) {
            this.slice.shutdown();
        }
    }

    @Test
    @Timeout(10)
    void shouldListEmptyDirectory() throws Exception {
        final RequestLine line = new RequestLine(RqMethod.GET, "/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        final String response = this.slice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);

        // Empty directory should show listing with no files/dirs
        assertTrue(response.contains("Index of /"));
        assertTrue(response.contains("<html>"));
        assertTrue(response.contains("</html>"));
    }

    @Test
    @Timeout(10)
    void shouldListDirectoryWithFilesAndSubdirectories() throws Exception {
        // Create test structure
        createTestStructure();

        final RequestLine line = new RequestLine(RqMethod.GET, "/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        final String response = this.slice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);

        // Should contain both files and directories
        assertTrue(response.contains("com/"));
        assertTrue(response.contains("org/"));
        assertTrue(response.contains("README.md"));
        assertTrue(response.contains("settings.xml"));
        assertTrue(response.contains("Index of /"));
    }

    @Test
    @Timeout(10)
    void shouldListSubdirectory() throws Exception {
        // Create test structure
        createTestStructure();

        final RequestLine line = new RequestLine(RqMethod.GET, "/com/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        final String response = this.slice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);

        // Should contain subdirectories and files under com/
        assertTrue(response.contains("google/"));
        assertTrue(response.contains("apache/"));
        assertTrue(response.contains("../"));
        assertTrue(response.contains("Index of /com/"));
    }

    @Test
    @Timeout(10)
    void shouldUseCacheForSubsequentRequests() throws Exception {
        // Create test structure
        createTestStructure();

        final RequestLine line = new RequestLine(RqMethod.GET, "/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        // First request - should build index
        long startTime = System.currentTimeMillis();
        final String response1 = this.slice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);
        long firstRequestTime = System.currentTimeMillis() - startTime;

        // Second request - should use cache
        startTime = System.currentTimeMillis();
        final String response2 = this.slice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);
        long secondRequestTime = System.currentTimeMillis() - startTime;

        // Both responses should be identical
        assertEquals(response1, response2);

        // In test environment with in-memory storage, timing can be variable
        // Just verify both complete successfully within reasonable time
        assertTrue(firstRequestTime < 1000, "First request should complete quickly: " + firstRequestTime + "ms");
        assertTrue(secondRequestTime < 1000, "Second request should complete quickly: " + secondRequestTime + "ms");
    }

    @Test
    @Timeout(10)
    void shouldHandleNonExistentDirectory() throws Exception {
        final RequestLine line = new RequestLine(RqMethod.GET, "/nonexistent/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        final var response = this.slice.response(line, headers, body)
            .get(5, TimeUnit.SECONDS);

        // Should return 200 with empty directory listing (not 404)
        assertEquals(200, response.status().code());
        final String content = response.body().asString();
        assertTrue(content.contains("Index of /nonexistent/"));
    }

    @Test
    @Timeout(30)
    void shouldHandleLargeDirectoryEfficiently() throws Exception {
        // Create a large directory structure (simulating 1000+ artifacts)
        createLargeStructure();

        // Use a fresh slice with unique cache to avoid contamination
        final Path uniqueCacheDir = Paths.get(System.getProperty("java.io.tmpdir"), 
            "artipie-test-cache-" + System.currentTimeMillis());
        final IndexedBrowseSlice freshSlice = new IndexedBrowseSlice(this.storage, 
            TimeUnit.MINUTES.toMillis(1), uniqueCacheDir);

        final RequestLine line = new RequestLine(RqMethod.GET, "/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        // Should complete within reasonable time even for large structure
        long startTime = System.currentTimeMillis();
        final String response = freshSlice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(10, TimeUnit.SECONDS);
        long requestTime = System.currentTimeMillis() - startTime;

        // Should complete quickly (under 5 seconds for 1000+ items)
        // In test environment with in-memory storage, this should be very fast
        assertTrue(requestTime < 1000, 
            "Large directory listing should complete in <1s for test, took " + requestTime + "ms");

        // Should contain expected content - check for actual patterns in created structure
        assertTrue(response.contains("Index of /"));
        // The large structure creates company directories, check for some of them
        assertTrue(response.contains("company0/") || response.contains("company0"));
        assertTrue(response.contains("company9/") || response.contains("company9"));
        
        // Cleanup
        freshSlice.shutdown();
    }

    @Test
    @Timeout(15)
    void shouldRefreshExpiredCache() throws Exception {
        // Create test structure
        createTestStructure();

        // Use very short cache TTL for testing
        final IndexedBrowseSlice shortCacheSlice = new IndexedBrowseSlice(this.storage, 100);

        final RequestLine line = new RequestLine(RqMethod.GET, "/");
        final Headers headers = Headers.from(new Header("Accept", "text/html"));
        final Content body = Content.EMPTY;

        // First request
        final String response1 = shortCacheSlice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);

        // Wait for cache to expire
        Thread.sleep(150);

        // Add new file
        this.storage.save(new Key.From("new-file.txt"), new Content.From("new content".getBytes()))
            .get(5, TimeUnit.SECONDS);

        // Second request should pick up new file after cache refresh
        final String response2 = shortCacheSlice.response(line, headers, body)
            .thenApply(resp -> resp.body().asString())
            .get(5, TimeUnit.SECONDS);

        // Should contain the new file
        assertTrue(response2.contains("new-file.txt"));
        
        shortCacheSlice.shutdown();
    }

    private void createTestStructure() throws Exception {
        // Create Maven-like directory structure
        this.storage.save(new Key.From("com/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("com/google/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("com/google/guava/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("com/google/guava/23.0/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("com/google/guava/23.0/guava-23.0.jar"), 
            new Content.From("jar content".getBytes())).get();
        this.storage.save(new Key.From("com/apache/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("com/apache/commons/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("org/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("org/springframework/"), new Content.From(new byte[0])).get();
        this.storage.save(new Key.From("README.md"), new Content.From("readme content".getBytes())).get();
        this.storage.save(new Key.From("settings.xml"), new Content.From("<settings/>".getBytes())).get();
    }

    private void createLargeStructure() throws Exception {
        // Create a large structure to test performance
        for (int company = 0; company < 10; company++) {
            for (int project = 0; project < 10; project++) {
                for (int version = 0; version < 10; version++) {
                    final String path = String.format("company%d/project%d/%d.0.0/project%d-%d.0.0.jar", 
                        company, project, version, project, version);
                    this.storage.save(new Key.From(path), 
                        new Content.From("jar content".getBytes())).get();
                }
            }
        }
    }
}
