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
package com.auto1.pantera.cooldown.metadata;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests that when upstream publishes a new version between two metadata
 * queries, the second query (after cache invalidation) returns the new
 * version in the filtered response.
 *
 * <p>The cache in {@link FilteredMetadataCache} is keyed by
 * {@code (repoType, repoName, packageName)} and stores the filtered
 * response bytes, so a plain second request would return the cached
 * T1 bytes. Production wires an explicit {@code invalidate(...)} hook
 * (called on block/unblock events, and on upstream-driven re-fetch).
 * This test exercises that hook directly: mutate the upstream fixture,
 * invalidate, and assert the recomputed response includes the new
 * version. No reliance on TTL expiry.</p>
 *
 * @since 1.0
 */
final class UpstreamPublishReEvalTest {

    private static final String PKG = "test-pkg";

    private MetadataFilterService service;
    private NoBlockCooldownService cooldownService;
    private CooldownSettings settings;
    private CooldownCache cooldownCache;
    private FilteredMetadataCache metadataCache;

    @BeforeEach
    void setUp() {
        this.cooldownService = new NoBlockCooldownService();
        this.settings = new CooldownSettings(true, Duration.ofDays(7));
        this.cooldownCache = new CooldownCache();
        this.metadataCache = new FilteredMetadataCache();
        this.service = new MetadataFilterService(
            this.cooldownService,
            this.settings,
            this.cooldownCache,
            this.metadataCache,
            ForkJoinPool.commonPool(),
            50
        );
    }

    /**
     * Scenario: upstream state mutates between T1 and T2.
     *  T1 → upstream has {1.0, 1.1}. Filtered response contains both.
     *  Simulate upstream publishing 1.2 (mutate the shared fixture).
     *  Call {@link MetadataFilterService#invalidate(String, String, String)}
     *    to mirror the production re-fetch path.
     *  T2 → upstream has {1.0, 1.1, 1.2}. Filtered response contains all 3.
     */
    @Test
    void newUpstreamVersionAppearsAfterInvalidate() throws Exception {
        // Shared mutable fixture: the "upstream" view of the package.
        final AtomicReference<UpstreamState> upstream = new AtomicReference<>(
            new UpstreamState(
                new ArrayList<>(Arrays.asList("1.0.0", "1.1.0")),
                "1.1.0",
                // All versions old enough to NOT be blocked under the 7d policy.
                Map.of(
                    "1.0.0", Instant.now().minus(Duration.ofDays(60)),
                    "1.1.0", Instant.now().minus(Duration.ofDays(30))
                )
            )
        );

        final MutableUpstreamParser parser = new MutableUpstreamParser(upstream);
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector(upstream);

        // --- T1: upstream = {1.0.0, 1.1.0} ---
        final byte[] resultT1 = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw-T1".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        final String bodyT1 = new String(resultT1, StandardCharsets.UTF_8);
        assertThat(
            "T1 response contains 1.0.0",
            bodyT1.contains("1.0.0"), equalTo(true)
        );
        assertThat(
            "T1 response contains 1.1.0",
            bodyT1.contains("1.1.0"), equalTo(true)
        );
        assertThat(
            "T1 response does NOT yet contain unpublished 1.2.0",
            bodyT1.contains("1.2.0"), equalTo(false)
        );
        assertThat(
            "T1: no versions blocked (all older than 7d)",
            filter.lastBlockedVersions.isEmpty(), equalTo(true)
        );
        final int parseCountAfterT1 = parser.parseCount;

        // --- Upstream publishes 1.2.0 (mutate the fixture) ---
        final Map<String, Instant> newDates = new HashMap<>(upstream.get().releaseDates);
        newDates.put("1.2.0", Instant.now().minus(Duration.ofDays(30)));
        upstream.set(new UpstreamState(
            new ArrayList<>(Arrays.asList("1.0.0", "1.1.0", "1.2.0")),
            "1.2.0",
            newDates
        ));

        // Sanity: a request WITHOUT invalidation would hit cache and NOT see 1.2.0
        // — verify the cache is doing its job before we invalidate.
        final byte[] stale = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw-T1".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();
        assertThat(
            "without invalidation, cache returns stale T1 bytes (no 1.2.0)",
            new String(stale, StandardCharsets.UTF_8).contains("1.2.0"), equalTo(false)
        );
        assertThat(
            "without invalidation, parser was NOT called again",
            parser.parseCount, equalTo(parseCountAfterT1)
        );

        // --- Production re-eval trigger: invalidate after upstream change ---
        this.service.invalidate("npm", "test-repo", PKG);

        // --- T2: upstream = {1.0.0, 1.1.0, 1.2.0} ---
        final byte[] resultT2 = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw-T2".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat(
            "invalidate forced a re-parse at T2",
            parser.parseCount, equalTo(parseCountAfterT1 + 1)
        );

        final String bodyT2 = new String(resultT2, StandardCharsets.UTF_8);
        assertThat(
            "T2 response contains 1.0.0",
            bodyT2.contains("1.0.0"), equalTo(true)
        );
        assertThat(
            "T2 response contains 1.1.0",
            bodyT2.contains("1.1.0"), equalTo(true)
        );
        assertThat(
            "T2 response contains newly-published 1.2.0",
            bodyT2.contains("1.2.0"), equalTo(true)
        );
        assertThat(
            "T2: last parsed version list size is 3",
            parser.lastParsed.size(), equalTo(3)
        );
        assertThat(
            "T2: no versions blocked",
            filter.lastBlockedVersions.isEmpty(), equalTo(true)
        );
    }

    // --- Test fixtures ---

    /**
     * Immutable snapshot of what "upstream" currently exposes for the package.
     */
    private static final class UpstreamState {
        final List<String> versions;
        final String latest;
        final Map<String, Instant> releaseDates;

        UpstreamState(
            final List<String> versions,
            final String latest,
            final Map<String, Instant> releaseDates
        ) {
            this.versions = versions;
            this.latest = latest;
            this.releaseDates = releaseDates;
        }
    }

    /**
     * Parser that reads from a shared {@link AtomicReference} each time
     * it parses, simulating a fresh upstream fetch on every cache miss.
     * The raw bytes parameter is ignored — this isolates the test from
     * HTTP layer details and lets us mutate upstream state cleanly.
     */
    private static final class MutableUpstreamParser implements MetadataParser<List<String>> {
        private final AtomicReference<UpstreamState> upstream;
        int parseCount;
        List<String> lastParsed = Collections.emptyList();

        MutableUpstreamParser(final AtomicReference<UpstreamState> upstream) {
            this.upstream = upstream;
        }

        @Override
        public List<String> parse(final byte[] bytes) {
            this.parseCount++;
            this.lastParsed = new ArrayList<>(this.upstream.get().versions);
            return this.lastParsed;
        }

        @Override
        public List<String> extractVersions(final List<String> metadata) {
            return metadata;
        }

        @Override
        public Optional<String> getLatestVersion(final List<String> metadata) {
            return Optional.ofNullable(this.upstream.get().latest);
        }

        @Override
        public Map<String, Instant> extractReleaseDates(final List<String> metadata) {
            return new HashMap<>(this.upstream.get().releaseDates);
        }

        @Override
        public String contentType() {
            return "application/json";
        }
    }

    /**
     * Inspector that reads release dates from the same shared fixture —
     * so an upstream mutation is visible to both the parser and the
     * cooldown evaluation path.
     */
    private static final class TestCooldownInspector implements CooldownInspector {
        private final AtomicReference<UpstreamState> upstream;

        TestCooldownInspector(final AtomicReference<UpstreamState> upstream) {
            this.upstream = upstream;
        }

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(
                Optional.ofNullable(this.upstream.get().releaseDates.get(version))
            );
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    /**
     * Cooldown service that never blocks — the upstream-publish scenario
     * is about cache re-evaluation, not about block decisions.
     */
    private static final class NoBlockCooldownService implements CooldownService {
        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request,
            final CooldownInspector inspector
        ) {
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
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            String repoType, String repoName
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private static final class TestMetadataFilter implements MetadataFilter<List<String>> {
        Set<String> lastBlockedVersions = new java.util.HashSet<>();

        @Override
        public List<String> filter(final List<String> metadata, final Set<String> blockedVersions) {
            this.lastBlockedVersions = blockedVersions;
            return metadata.stream()
                .filter(v -> !blockedVersions.contains(v))
                .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public List<String> updateLatest(final List<String> metadata, final String newLatest) {
            return metadata;
        }
    }

    private static final class TestMetadataRewriter implements MetadataRewriter<List<String>> {
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
