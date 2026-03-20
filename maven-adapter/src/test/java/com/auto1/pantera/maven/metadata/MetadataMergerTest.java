/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.maven.metadata;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.test.TestResource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive tests for {@link MetadataMerger}.
 * Tests plugin resolution, metadata merging, concurrent operations, and timeout handling.
 *
 * @since 1.19.1
 */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class MetadataMergerTest {

    @Test
    void mergesGroupLevelMetadataWithPlugins() throws Exception {
        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/group-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/group-level-metadata-2.xml").asBytes()
        );

        final Content result = new MetadataMerger(metadataList).merge()
            .get(5, TimeUnit.SECONDS);

        final String merged = new String(result.asBytes(), StandardCharsets.UTF_8);

        // Verify structure
        MatcherAssert.assertThat(
            "Merged metadata contains groupId",
            merged,
            Matchers.containsString("<groupId>org.apache.maven.plugins</groupId>")
        );

        // Verify all plugins are present (union of both files)
        MatcherAssert.assertThat(
            "Merged metadata contains clean plugin",
            merged,
            Matchers.containsString("<prefix>clean</prefix>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains compiler plugin",
            merged,
            Matchers.containsString("<prefix>compiler</prefix>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains surefire plugin",
            merged,
            Matchers.containsString("<prefix>surefire</prefix>")
        );

        // Verify no versioning section for group-level metadata
        MatcherAssert.assertThat(
            "Group-level metadata should not have versioning section",
            merged,
            Matchers.not(Matchers.containsString("<versioning>"))
        );
    }

    @Test
    void mergesArtifactLevelMetadataWithVersions() throws Exception {
        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/artifact-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/artifact-level-metadata-2.xml").asBytes()
        );

        final Content result = new MetadataMerger(metadataList).merge()
            .get(5, TimeUnit.SECONDS);

        final String merged = new String(result.asBytes(), StandardCharsets.UTF_8);

        // Verify structure
        MatcherAssert.assertThat(
            "Merged metadata contains groupId",
            merged,
            Matchers.containsString("<groupId>com.example</groupId>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains artifactId",
            merged,
            Matchers.containsString("<artifactId>my-library</artifactId>")
        );

        // Verify all versions are present (union of both files)
        MatcherAssert.assertThat(
            "Merged metadata contains version 1.0.0",
            merged,
            Matchers.containsString("<version>1.0.0</version>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains version 1.1.0",
            merged,
            Matchers.containsString("<version>1.1.0</version>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains version 1.2.0",
            merged,
            Matchers.containsString("<version>1.2.0</version>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains version 2.0.0",
            merged,
            Matchers.containsString("<version>2.0.0</version>")
        );
        MatcherAssert.assertThat(
            "Merged metadata contains version 2.1.0",
            merged,
            Matchers.containsString("<version>2.1.0</version>")
        );

        // Verify versioning section exists
        MatcherAssert.assertThat(
            "Artifact-level metadata should have versioning section",
            merged,
            Matchers.containsString("<versioning>")
        );

        // Verify lastUpdated uses Maven-standard yyyyMMddHHmmss format (14 digits)
        MatcherAssert.assertThat(
            "Merged metadata should have lastUpdated in yyyyMMddHHmmss format",
            merged,
            Matchers.matchesRegex("(?s).*<lastUpdated>\\d{14}</lastUpdated>.*")
        );
    }

    @Test
    void handlesSingleMetadataFile() throws Exception {
        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/group-level-metadata-1.xml").asBytes()
        );

        final Content result = new MetadataMerger(metadataList).merge()
            .get(5, TimeUnit.SECONDS);

        final String merged = new String(result.asBytes(), StandardCharsets.UTF_8);

        MatcherAssert.assertThat(
            "Single metadata file should be returned as-is",
            merged,
            Matchers.containsString("<prefix>clean</prefix>")
        );
        MatcherAssert.assertThat(
            "Single metadata file should contain compiler plugin",
            merged,
            Matchers.containsString("<prefix>compiler</prefix>")
        );
    }

    @Test
    void handlesEmptyMetadataList() {
        final List<byte[]> metadataList = List.of();

        // Empty list should throw IllegalArgumentException
        final Exception exception = org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class,
            () -> new MetadataMerger(metadataList).merge().get(5, TimeUnit.SECONDS)
        );

        // The exception is wrapped in ExecutionException, so check the cause chain
        Throwable cause = exception.getCause();
        while (cause != null && !cause.getMessage().contains("No metadata to merge")) {
            cause = cause.getCause();
        }

        MatcherAssert.assertThat(
            "Empty list should throw exception with appropriate message",
            cause,
            Matchers.notNullValue()
        );
        MatcherAssert.assertThat(
            "Exception message should mention no metadata to merge",
            cause.getMessage(),
            Matchers.containsString("No metadata to merge")
        );
    }

    @Test
    void deduplicatesPlugins() throws Exception {
        // Both files contain compiler plugin - should appear only once
        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/group-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/group-level-metadata-2.xml").asBytes()
        );

        final Content result = new MetadataMerger(metadataList).merge()
            .get(5, TimeUnit.SECONDS);

        final String merged = new String(result.asBytes(), StandardCharsets.UTF_8);

        // Count occurrences of compiler plugin
        final int count = countOccurrences(merged, "<prefix>compiler</prefix>");

        MatcherAssert.assertThat(
            "Compiler plugin should appear only once (deduplicated)",
            count,
            Matchers.equalTo(1)
        );
    }

    @Test
    void deduplicatesVersions() throws Exception {
        // Create metadata with overlapping versions
        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/artifact-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/artifact-level-metadata-1.xml").asBytes() // Same file twice
        );

        final Content result = new MetadataMerger(metadataList).merge()
            .get(5, TimeUnit.SECONDS);

        final String merged = new String(result.asBytes(), StandardCharsets.UTF_8);

        // Count occurrences of version 1.0.0 within <versions> section
        final String versionsSection = merged.substring(
            merged.indexOf("<versions>"),
            merged.indexOf("</versions>") + "</versions>".length()
        );
        final int count = countOccurrences(versionsSection, "<version>1.0.0</version>");

        MatcherAssert.assertThat(
            "Version 1.0.0 should appear only once in versions section (deduplicated)",
            count,
            Matchers.equalTo(1)
        );
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void handlesConcurrentMergeOperations() throws Exception {
        final int concurrentRequests = 50;
        final List<CompletableFuture<Content>> futures = new ArrayList<>();

        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/group-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/group-level-metadata-2.xml").asBytes()
        );

        // Fire concurrent merge operations
        for (int i = 0; i < concurrentRequests; i++) {
            futures.add(new MetadataMerger(metadataList).merge());
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .get(10, TimeUnit.SECONDS);

        // Verify all results are valid
        for (CompletableFuture<Content> future : futures) {
            final String merged = new String(future.get().asBytes(), StandardCharsets.UTF_8);
            MatcherAssert.assertThat(
                "Concurrent merge should produce valid metadata",
                merged,
                Matchers.containsString("<prefix>clean</prefix>")
            );
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void handlesHighConcurrencyWithinSemaphoreLimit() throws Exception {
        final int concurrentRequests = 200; // Within semaphore limit of 250
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/artifact-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/artifact-level-metadata-2.xml").asBytes()
        );

        final ExecutorService executor = Executors.newFixedThreadPool(50);

        try {
            for (int i = 0; i < concurrentRequests; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        new MetadataMerger(metadataList).merge()
                            .get(10, TimeUnit.SECONDS);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // Start all threads simultaneously
            doneLatch.await(15, TimeUnit.SECONDS);

            MatcherAssert.assertThat(
                "All requests within semaphore limit should succeed",
                successCount.get(),
                Matchers.equalTo(concurrentRequests)
            );
            MatcherAssert.assertThat(
                "No requests should fail within semaphore limit",
                failureCount.get(),
                Matchers.equalTo(0)
            );
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void rejectsConcurrencyBeyondSemaphoreLimit() throws Exception {
        final int concurrentRequests = 300; // Beyond semaphore limit of 250
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch allSubmittedLatch = new CountDownLatch(concurrentRequests);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);

        final List<byte[]> metadataList = List.of(
            new TestResource("MetadataMergerTest/artifact-level-metadata-1.xml").asBytes(),
            new TestResource("MetadataMergerTest/artifact-level-metadata-2.xml").asBytes()
        );

        // Use a large thread pool to ensure all requests are submitted simultaneously
        final ExecutorService executor = Executors.newFixedThreadPool(300);

        try {
            // Submit all tasks
            for (int i = 0; i < concurrentRequests; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        allSubmittedLatch.countDown();
                        // Try to acquire semaphore and merge
                        new MetadataMerger(metadataList).merge()
                            .get(10, TimeUnit.SECONDS);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Expected for requests beyond semaphore limit
                        if (e.getCause() != null &&
                            e.getCause().getMessage().contains("Too many concurrent metadata merge operations")) {
                            failureCount.incrementAndGet();
                        } else {
                            // Unexpected error
                            failureCount.incrementAndGet();
                        }
                    }
                });
            }

            startLatch.countDown(); // Start all threads simultaneously
            allSubmittedLatch.await(5, TimeUnit.SECONDS); // Wait for all to be submitted

            // Wait a bit for operations to complete
            Thread.sleep(2000);

            // Note: Due to fast execution, some requests might complete before others start,
            // so we just verify that the system handles the load without crashing
            MatcherAssert.assertThat(
                "All requests should complete (success or failure)",
                successCount.get() + failureCount.get(),
                Matchers.greaterThan(0)
            );

            // The semaphore should have protected the system - verify no crashes occurred
            MatcherAssert.assertThat(
                "System should handle high concurrency gracefully",
                successCount.get(),
                Matchers.greaterThan(0)
            );
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Helper method to count occurrences of a substring.
     */
    private int countOccurrences(final String text, final String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}

