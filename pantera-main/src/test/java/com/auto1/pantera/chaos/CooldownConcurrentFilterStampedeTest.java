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
package com.auto1.pantera.chaos;

import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataFilterService;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * Chaos test: 100 concurrent requests for the same uncached package metadata.
 *
 * <p>Verifies that the stampede-prevention mechanism in
 * {@link FilteredMetadataCache} deduplicates concurrent requests so the
 * parser runs at most a small number of times (ideally once), not 100.</p>
 *
 * <p>Uses the Go adapter format (simplest: plain-text version list) for
 * predictable parsing behaviour.</p>
 *
 * @since 2.2.0
 */
@Tag("Chaos")
final class CooldownConcurrentFilterStampedeTest {

    /**
     * Number of concurrent callers.
     */
    private static final int CONCURRENT = 100;

    /**
     * Go-style metadata: 10 versions.
     */
    private static final String GO_METADATA =
        "v0.1.0\nv0.2.0\nv0.3.0\nv0.4.0\nv0.5.0\n"
        + "v0.6.0\nv0.7.0\nv0.8.0\nv0.9.0\nv1.0.0";

    /**
     * 100 concurrent requests for the same uncached metadata: the parser
     * must run a minimal number of times (stampede dedup).
     */
    @Test
    void hundredConcurrentRequestsDeduplicated() throws Exception {
        final CountingCooldownService cooldownService = new CountingCooldownService();
        cooldownService.block("test-pkg", "v1.0.0");

        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        final CooldownCache cooldownCache = new CooldownCache();
        final FilteredMetadataCache metadataCache = new FilteredMetadataCache();
        final MetadataFilterService service = new MetadataFilterService(
            cooldownService, settings, cooldownCache, metadataCache,
            ForkJoinPool.commonPool(), 50
        );

        final CountingGoParser parser = new CountingGoParser();
        final GoFilter filter = new GoFilter();
        final GoRewriter rewriter = new GoRewriter();
        final NoopInspector inspector = new NoopInspector();

        final byte[] rawBytes = GO_METADATA.getBytes(StandardCharsets.UTF_8);
        final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT);
        final CountDownLatch startGate = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        final CompletableFuture<byte[]>[] futures = new CompletableFuture[CONCURRENT];

        for (int i = 0; i < CONCURRENT; i++) {
            final CompletableFuture<byte[]> future = new CompletableFuture<>();
            futures[i] = future;
            executor.submit(() -> {
                try {
                    // All threads wait at the gate, then fire simultaneously
                    startGate.await(5, TimeUnit.SECONDS);
                    final byte[] result = service.filterMetadata(
                        "go", "go-repo", "test-pkg",
                        rawBytes, parser, filter, rewriter,
                        Optional.of(inspector)
                    ).get(10, TimeUnit.SECONDS);
                    future.complete(result);
                } catch (final Exception ex) {
                    future.completeExceptionally(ex);
                }
            });
        }

        // Open the gate — all 100 threads fire
        startGate.countDown();

        // Wait for all to complete
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // All 100 requests must succeed
        for (int i = 0; i < CONCURRENT; i++) {
            final byte[] result = futures[i].get();
            final String output = new String(result, StandardCharsets.UTF_8);
            assertThat("Request " + i + ": v1.0.0 must be filtered",
                output, not(containsString("v1.0.0")));
            assertThat("Request " + i + ": must have content",
                result.length, greaterThan(0));
        }

        // The parser should have run a small number of times,
        // far less than 100. With stampede dedup the first thread
        // loads and all others coalesce. We allow a generous margin
        // (up to 5) for race-condition timing between the first load
        // completing and the other threads checking the inflight map.
        final int parseCount = parser.parseCount.get();
        assertThat("Parser should run far fewer than " + CONCURRENT + " times "
                + "(stampede dedup). Actual: " + parseCount,
            parseCount, lessThanOrEqualTo(5));
    }

    /**
     * All concurrent requests get the same filtered content.
     */
    @Test
    void allConcurrentRequestsGetConsistentResults() throws Exception {
        final CountingCooldownService cooldownService = new CountingCooldownService();
        cooldownService.block("pkg2", "v0.5.0");
        cooldownService.block("pkg2", "v0.9.0");

        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        final MetadataFilterService service = new MetadataFilterService(
            cooldownService, settings, new CooldownCache(),
            new FilteredMetadataCache(), ForkJoinPool.commonPool(), 50
        );

        final CountingGoParser parser = new CountingGoParser();
        final GoFilter filter = new GoFilter();
        final GoRewriter rewriter = new GoRewriter();
        final NoopInspector inspector = new NoopInspector();

        final byte[] rawBytes = GO_METADATA.getBytes(StandardCharsets.UTF_8);
        final int threads = 50;
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        final CountDownLatch startGate = new CountDownLatch(1);

        @SuppressWarnings("unchecked")
        final CompletableFuture<byte[]>[] futures = new CompletableFuture[threads];

        for (int i = 0; i < threads; i++) {
            final CompletableFuture<byte[]> future = new CompletableFuture<>();
            futures[i] = future;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    final byte[] result = service.filterMetadata(
                        "go", "go-repo", "pkg2", rawBytes,
                        parser, filter, rewriter, Optional.of(inspector)
                    ).get(10, TimeUnit.SECONDS);
                    future.complete(result);
                } catch (final Exception ex) {
                    future.completeExceptionally(ex);
                }
            });
        }

        startGate.countDown();
        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        executor.shutdown();

        // All results must be byte-identical
        final byte[] reference = futures[0].get();
        for (int i = 1; i < threads; i++) {
            final byte[] result = futures[i].get();
            assertThat("Result " + i + " must match reference",
                new String(result, StandardCharsets.UTF_8),
                equalTo(new String(reference, StandardCharsets.UTF_8)));
        }
    }

    // --- Counting Go parser ---

    private static final class CountingGoParser implements MetadataParser<List<String>> {
        final AtomicInteger parseCount = new AtomicInteger();

        @Override
        public List<String> parse(final byte[] bytes) {
            this.parseCount.incrementAndGet();
            if (bytes == null || bytes.length == 0) {
                return Collections.emptyList();
            }
            final String body = new String(bytes, StandardCharsets.UTF_8);
            final String[] lines = body.split("\n", -1);
            final List<String> versions = new ArrayList<>(lines.length);
            for (final String line : lines) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    versions.add(trimmed);
                }
            }
            return versions;
        }

        @Override
        public List<String> extractVersions(final List<String> metadata) {
            return metadata == null ? Collections.emptyList() : List.copyOf(metadata);
        }

        @Override
        public Optional<String> getLatestVersion(final List<String> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(metadata.get(metadata.size() - 1));
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    }

    private static final class GoFilter implements MetadataFilter<List<String>> {
        @Override
        public List<String> filter(final List<String> metadata, final Set<String> blocked) {
            if (blocked.isEmpty()) {
                return metadata;
            }
            final List<String> result = new ArrayList<>();
            for (final String v : metadata) {
                if (!blocked.contains(v)) {
                    result.add(v);
                }
            }
            return result;
        }

        @Override
        public List<String> updateLatest(final List<String> metadata, final String newLatest) {
            return metadata;
        }
    }

    private static final class GoRewriter implements MetadataRewriter<List<String>> {
        @Override
        public byte[] rewrite(final List<String> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return new byte[0];
            }
            return String.join("\n", metadata).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "text/plain";
        }
    }

    private static final class CountingCooldownService implements CooldownService {
        private final Set<String> blocked = new HashSet<>();

        void block(final String pkg, final String version) {
            this.blocked.add(pkg + "@" + version);
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            final String key = request.artifact() + "@" + request.version();
            if (this.blocked.contains(key)) {
                return CompletableFuture.completedFuture(
                    CooldownResult.blocked(new CooldownBlock(
                        request.repoType(), request.repoName(),
                        request.artifact(), request.version(),
                        CooldownReason.FRESH_RELEASE, Instant.now(),
                        Instant.now().plus(Duration.ofDays(7)),
                        Collections.emptyList()
                    ))
                );
            }
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            String rt, String rn, String a, String v, String actor
        ) {
            this.blocked.remove(a + "@" + v);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(String rt, String rn, String actor) {
            this.blocked.clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(String rt, String rn) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private static final class NoopInspector implements CooldownInspector {
        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(String artifact, String version) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            String artifact, String version
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
