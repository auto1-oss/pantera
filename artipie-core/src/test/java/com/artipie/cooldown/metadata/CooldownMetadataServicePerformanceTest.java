/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

import com.artipie.cooldown.CooldownCache;
import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResult;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

/**
 * Performance tests for {@link CooldownMetadataServiceImpl}.
 * 
 * <p>Performance requirements:</p>
 * <ul>
 *   <li>P99 latency: &lt; 200ms for metadata filtering</li>
 *   <li>Throughput: 1,500 requests/second</li>
 * </ul>
 *
 * @since 1.0
 */
@Tag("performance")
final class CooldownMetadataServicePerformanceTest {

    /**
     * Maximum allowed P99 latency in milliseconds.
     */
    private static final long MAX_P99_LATENCY_MS = 200;

    /**
     * Number of iterations for latency tests.
     */
    private static final int LATENCY_ITERATIONS = 100;

    /**
     * Number of warm-up iterations.
     */
    private static final int WARMUP_ITERATIONS = 10;

    private CooldownMetadataServiceImpl service;
    private FastCooldownService cooldownService;

    @BeforeEach
    void setUp() {
        this.cooldownService = new FastCooldownService();
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        final CooldownCache cooldownCache = new CooldownCache();
        // Use fresh metadata cache for each test to measure actual filtering time
        final FilteredMetadataCache metadataCache = new FilteredMetadataCache();
        this.service = new CooldownMetadataServiceImpl(
            this.cooldownService,
            settings,
            cooldownCache,
            metadataCache,
            ForkJoinPool.commonPool(),
            50
        );
    }

    @Test
    void filterSmallMetadataUnder50ms() throws Exception {
        // 50 versions - typical small package
        final int versionCount = 50;
        final PerformanceMetadataParser parser = new PerformanceMetadataParser(versionCount);
        final PerformanceMetadataFilter filter = new PerformanceMetadataFilter();
        final PerformanceMetadataRewriter rewriter = new PerformanceMetadataRewriter();

        // Block 10% of versions
        for (int i = 0; i < versionCount / 10; i++) {
            this.cooldownService.blockVersion("perf-pkg", "1.0." + i);
        }

        // Warm up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            this.service.invalidate("npm", "perf-repo", "perf-pkg");
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
        }

        // Measure
        final List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < LATENCY_ITERATIONS; i++) {
            this.service.invalidate("npm", "perf-repo", "perf-pkg");
            final long start = System.nanoTime();
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
            final long durationMs = (System.nanoTime() - start) / 1_000_000;
            latencies.add(durationMs);
        }

        final long p99 = percentile(latencies, 99);
        System.out.printf("Small metadata (50 versions) - P50: %dms, P95: %dms, P99: %dms%n",
            percentile(latencies, 50), percentile(latencies, 95), p99);

        assertThat("P99 latency for small metadata should be < 50ms", p99, lessThan(50L));
    }

    @Test
    void filterMediumMetadataUnder100ms() throws Exception {
        // 200 versions - medium package
        final int versionCount = 200;
        final PerformanceMetadataParser parser = new PerformanceMetadataParser(versionCount);
        final PerformanceMetadataFilter filter = new PerformanceMetadataFilter();
        final PerformanceMetadataRewriter rewriter = new PerformanceMetadataRewriter();

        // Block 10% of versions
        for (int i = 0; i < versionCount / 10; i++) {
            this.cooldownService.blockVersion("perf-pkg", "1.0." + i);
        }

        // Warm up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            this.service.invalidate("npm", "perf-repo", "perf-pkg");
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
        }

        // Measure
        final List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < LATENCY_ITERATIONS; i++) {
            this.service.invalidate("npm", "perf-repo", "perf-pkg");
            final long start = System.nanoTime();
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
            final long durationMs = (System.nanoTime() - start) / 1_000_000;
            latencies.add(durationMs);
        }

        final long p99 = percentile(latencies, 99);
        System.out.printf("Medium metadata (200 versions) - P50: %dms, P95: %dms, P99: %dms%n",
            percentile(latencies, 50), percentile(latencies, 95), p99);

        assertThat("P99 latency for medium metadata should be < 100ms", p99, lessThan(100L));
    }

    @Test
    void filterLargeMetadataUnder200ms() throws Exception {
        // 1000 versions - large package (like @types/node)
        final int versionCount = 1000;
        final PerformanceMetadataParser parser = new PerformanceMetadataParser(versionCount);
        final PerformanceMetadataFilter filter = new PerformanceMetadataFilter();
        final PerformanceMetadataRewriter rewriter = new PerformanceMetadataRewriter();

        // Block 5% of versions
        for (int i = 0; i < versionCount / 20; i++) {
            this.cooldownService.blockVersion("perf-pkg", "1.0." + i);
        }

        // Warm up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            this.service.invalidate("npm", "perf-repo", "perf-pkg");
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
        }

        // Measure
        final List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < LATENCY_ITERATIONS; i++) {
            this.service.invalidate("npm", "perf-repo", "perf-pkg");
            final long start = System.nanoTime();
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
            final long durationMs = (System.nanoTime() - start) / 1_000_000;
            latencies.add(durationMs);
        }

        final long p99 = percentile(latencies, 99);
        System.out.printf("Large metadata (1000 versions) - P50: %dms, P95: %dms, P99: %dms%n",
            percentile(latencies, 50), percentile(latencies, 95), p99);

        // Note: With bounded evaluation (max 50 versions), even large packages should be fast
        assertThat("P99 latency for large metadata should be < 200ms", p99, lessThan(MAX_P99_LATENCY_MS));
    }

    @Test
    void cacheHitLatencyUnder5ms() throws Exception {
        final int versionCount = 100;
        final PerformanceMetadataParser parser = new PerformanceMetadataParser(versionCount);
        final PerformanceMetadataFilter filter = new PerformanceMetadataFilter();
        final PerformanceMetadataRewriter rewriter = new PerformanceMetadataRewriter();

        // Prime the cache
        this.service.filterMetadata(
            "npm", "perf-repo", "perf-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.empty()
        ).get();

        // Measure cache hits
        final List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < LATENCY_ITERATIONS; i++) {
            final long start = System.nanoTime();
            this.service.filterMetadata(
                "npm", "perf-repo", "perf-pkg",
                "raw".getBytes(StandardCharsets.UTF_8),
                parser, filter, rewriter, Optional.empty()
            ).get();
            final long durationMs = (System.nanoTime() - start) / 1_000_000;
            latencies.add(durationMs);
        }

        final long p99 = percentile(latencies, 99);
        System.out.printf("Cache hit - P50: %dms, P95: %dms, P99: %dms%n",
            percentile(latencies, 50), percentile(latencies, 95), p99);

        assertThat("P99 latency for cache hit should be < 5ms", p99, lessThan(5L));
    }

    /**
     * Calculate percentile from sorted list.
     */
    private static long percentile(final List<Long> values, final int percentile) {
        final List<Long> sorted = values.stream().sorted().collect(Collectors.toList());
        final int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    // Fast test implementations

    /**
     * Fast cooldown service that returns immediately.
     */
    private static final class FastCooldownService implements CooldownService {
        private final Set<String> blockedVersions = new HashSet<>();

        void blockVersion(final String pkg, final String version) {
            this.blockedVersions.add(pkg + "@" + version);
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request,
            final CooldownInspector inspector
        ) {
            final String key = request.artifact() + "@" + request.version();
            if (this.blockedVersions.contains(key)) {
                return CompletableFuture.completedFuture(
                    CooldownResult.blocked(new com.artipie.cooldown.CooldownBlock(
                        request.repoType(), request.repoName(),
                        request.artifact(), request.version(),
                        com.artipie.cooldown.CooldownReason.FRESH_RELEASE,
                        Instant.now(), Instant.now().plus(Duration.ofDays(7)),
                        java.util.Collections.emptyList()
                    ))
                );
            }
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            String repoType, String repoName, String artifact, String version, String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(String repoType, String repoName, String actor) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<com.artipie.cooldown.CooldownBlock>> activeBlocks(
            String repoType, String repoName
        ) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }

    /**
     * Parser that generates N versions.
     */
    private static final class PerformanceMetadataParser implements MetadataParser<List<String>> {
        private final List<String> versions;

        PerformanceMetadataParser(final int count) {
            this.versions = IntStream.range(0, count)
                .mapToObj(i -> "1.0." + i)
                .collect(Collectors.toList());
        }

        @Override
        public List<String> parse(final byte[] bytes) {
            return this.versions;
        }

        @Override
        public List<String> extractVersions(final List<String> metadata) {
            return metadata;
        }

        @Override
        public Optional<String> getLatestVersion(final List<String> metadata) {
            return metadata.isEmpty() ? Optional.empty() : Optional.of(metadata.get(metadata.size() - 1));
        }

        @Override
        public String contentType() {
            return "application/json";
        }
    }

    /**
     * Fast filter implementation.
     */
    private static final class PerformanceMetadataFilter implements MetadataFilter<List<String>> {
        @Override
        public List<String> filter(final List<String> metadata, final Set<String> blockedVersions) {
            if (blockedVersions.isEmpty()) {
                return metadata;
            }
            return metadata.stream()
                .filter(v -> !blockedVersions.contains(v))
                .collect(Collectors.toList());
        }

        @Override
        public List<String> updateLatest(final List<String> metadata, final String newLatest) {
            return metadata;
        }
    }

    /**
     * Fast rewriter implementation.
     */
    private static final class PerformanceMetadataRewriter implements MetadataRewriter<List<String>> {
        @Override
        public byte[] rewrite(final List<String> metadata) {
            return String.join(",", metadata).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "application/json";
        }
    }
}
