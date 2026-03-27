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
package com.auto1.pantera.npm.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

/**
 * Performance tests for NPM cooldown metadata filtering.
 *
 * <p>Performance requirements:</p>
 * <ul>
 *   <li>P99 latency for small metadata (50 versions): &lt; 50ms</li>
 *   <li>P99 latency for medium metadata (200 versions): &lt; 100ms</li>
 *   <li>P99 latency for large metadata (1000 versions): &lt; 200ms</li>
 * </ul>
 *
 * @since 1.0
 */
@Tag("performance")
final class NpmCooldownPerformanceTest {

    /**
     * Number of iterations for latency tests.
     */
    private static final int ITERATIONS = 100;

    /**
     * Number of warm-up iterations.
     */
    private static final int WARMUP = 10;

    private NpmMetadataParser parser;
    private NpmMetadataFilter filter;
    private NpmMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.parser = new NpmMetadataParser();
        this.filter = new NpmMetadataFilter();
        this.rewriter = new NpmMetadataRewriter();
    }

    @Test
    void smallMetadataP99Under50ms() throws Exception {
        final byte[] metadata = generateNpmMetadata(50);
        final Set<String> blocked = generateBlockedVersions(50, 5); // 10% blocked

        // Warm up
        for (int i = 0; i < WARMUP; i++) {
            processMetadata(metadata, blocked);
        }

        // Measure
        final long[] latencies = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            final long start = System.nanoTime();
            processMetadata(metadata, blocked);
            latencies[i] = (System.nanoTime() - start) / 1_000_000; // ms
        }

        final long p99 = calculateP99(latencies);
        System.out.printf("Small metadata (50 versions) P99: %d ms%n", p99);
        assertThat("P99 for small metadata should be < 50ms", p99, lessThan(50L));
    }

    @Test
    void mediumMetadataP99Under100ms() throws Exception {
        final byte[] metadata = generateNpmMetadata(200);
        final Set<String> blocked = generateBlockedVersions(200, 20); // 10% blocked

        // Warm up
        for (int i = 0; i < WARMUP; i++) {
            processMetadata(metadata, blocked);
        }

        // Measure
        final long[] latencies = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            final long start = System.nanoTime();
            processMetadata(metadata, blocked);
            latencies[i] = (System.nanoTime() - start) / 1_000_000; // ms
        }

        final long p99 = calculateP99(latencies);
        System.out.printf("Medium metadata (200 versions) P99: %d ms%n", p99);
        assertThat("P99 for medium metadata should be < 100ms", p99, lessThan(100L));
    }

    @Test
    void largeMetadataP99Under200ms() throws Exception {
        final byte[] metadata = generateNpmMetadata(1000);
        final Set<String> blocked = generateBlockedVersions(1000, 100); // 10% blocked

        // Warm up
        for (int i = 0; i < WARMUP; i++) {
            processMetadata(metadata, blocked);
        }

        // Measure
        final long[] latencies = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            final long start = System.nanoTime();
            processMetadata(metadata, blocked);
            latencies[i] = (System.nanoTime() - start) / 1_000_000; // ms
        }

        final long p99 = calculateP99(latencies);
        System.out.printf("Large metadata (1000 versions) P99: %d ms%n", p99);
        assertThat("P99 for large metadata should be < 200ms", p99, lessThan(200L));
    }

    @Test
    void releaseDateExtractionPerformance() throws Exception {
        final byte[] metadata = generateNpmMetadata(500);

        // Warm up
        for (int i = 0; i < WARMUP; i++) {
            final JsonNode parsed = this.parser.parse(metadata);
            this.parser.releaseDates(parsed);
        }

        // Measure
        final long[] latencies = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            final long start = System.nanoTime();
            final JsonNode parsed = this.parser.parse(metadata);
            final Map<String, Instant> dates = this.parser.releaseDates(parsed);
            latencies[i] = (System.nanoTime() - start) / 1_000_000; // ms
        }

        final long p99 = calculateP99(latencies);
        System.out.printf("Release date extraction (500 versions) P99: %d ms%n", p99);
        assertThat("P99 for release date extraction should be < 100ms", p99, lessThan(100L));
    }

    @Test
    void filteringWithManyBlockedVersions() throws Exception {
        final byte[] metadata = generateNpmMetadata(500);
        final Set<String> blocked = generateBlockedVersions(500, 250); // 50% blocked

        // Warm up
        for (int i = 0; i < WARMUP; i++) {
            processMetadata(metadata, blocked);
        }

        // Measure
        final long[] latencies = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            final long start = System.nanoTime();
            processMetadata(metadata, blocked);
            latencies[i] = (System.nanoTime() - start) / 1_000_000; // ms
        }

        final long p99 = calculateP99(latencies);
        System.out.printf("Filtering 50%% blocked (500 versions) P99: %d ms%n", p99);
        assertThat("P99 for heavy filtering should be < 150ms", p99, lessThan(150L));
    }

    @Test
    void throughputTest() throws Exception {
        final byte[] metadata = generateNpmMetadata(100);
        final Set<String> blocked = generateBlockedVersions(100, 10);

        // Warm up
        for (int i = 0; i < WARMUP; i++) {
            processMetadata(metadata, blocked);
        }

        // Measure throughput over 1 second
        final long startTime = System.currentTimeMillis();
        int count = 0;
        while (System.currentTimeMillis() - startTime < 1000) {
            processMetadata(metadata, blocked);
            count++;
        }

        System.out.printf("Throughput: %d operations/second%n", count);
        assertThat("Should process at least 100 operations/second", count, org.hamcrest.Matchers.greaterThan(100));
    }

    /**
     * Process metadata through the full pipeline: parse -> filter -> rewrite.
     */
    private byte[] processMetadata(final byte[] rawMetadata, final Set<String> blocked) throws Exception {
        final JsonNode parsed = this.parser.parse(rawMetadata);
        final JsonNode filtered = this.filter.filter(parsed, blocked);
        
        // Update latest if needed
        final var latest = this.parser.getLatestVersion(parsed);
        JsonNode result = filtered;
        if (latest.isPresent() && blocked.contains(latest.get())) {
            final List<String> versions = this.parser.extractVersions(filtered);
            if (!versions.isEmpty()) {
                // Sort versions and get highest unblocked
                versions.sort((a, b) -> compareVersions(b, a)); // descending
                result = this.filter.updateLatest(filtered, versions.get(0));
            }
        }
        
        return this.rewriter.rewrite(result);
    }

    /**
     * Generate NPM metadata with specified number of versions.
     */
    private static byte[] generateNpmMetadata(final int versionCount) {
        final StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"perf-test-package\",");
        json.append("\"dist-tags\":{\"latest\":\"").append(versionCount - 1).append(".0.0\"},");
        json.append("\"versions\":{");
        
        for (int i = 0; i < versionCount; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(String.format("\"%d.0.0\":{\"name\":\"perf-test-package\",\"version\":\"%d.0.0\",", i, i));
            json.append("\"dist\":{\"tarball\":\"http://example.com/pkg.tgz\",\"shasum\":\"abc123\"}}");
        }
        json.append("},");
        
        json.append("\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\",\"modified\":\"2023-01-01T00:00:00.000Z\"");
        for (int i = 0; i < versionCount; i++) {
            json.append(String.format(",\"%d.0.0\":\"2020-01-01T00:00:00.000Z\"", i));
        }
        json.append("}}");
        
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generate a set of blocked version strings.
     */
    private static Set<String> generateBlockedVersions(final int totalVersions, final int blockedCount) {
        final Set<String> blocked = new HashSet<>();
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        
        // Block the newest versions (most realistic scenario)
        for (int i = totalVersions - blockedCount; i < totalVersions; i++) {
            blocked.add(String.format("%d.0.0", i));
        }
        
        return blocked;
    }

    /**
     * Calculate P99 latency from array of latencies.
     */
    private static long calculateP99(final long[] latencies) {
        final long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        final int p99Index = (int) Math.ceil(sorted.length * 0.99) - 1;
        return sorted[Math.max(0, p99Index)];
    }

    /**
     * Simple version comparison for sorting.
     */
    private static int compareVersions(final String a, final String b) {
        final String[] partsA = a.split("\\.");
        final String[] partsB = b.split("\\.");
        
        for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
            try {
                final int numA = Integer.parseInt(partsA[i]);
                final int numB = Integer.parseInt(partsB[i]);
                if (numA != numB) {
                    return Integer.compare(numA, numB);
                }
            } catch (NumberFormatException e) {
                final int cmp = partsA[i].compareTo(partsB[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return Integer.compare(partsA.length, partsB.length);
    }
}
