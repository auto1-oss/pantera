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
package com.auto1.pantera.cooldown.cache;

import com.auto1.pantera.cooldown.api.CooldownBlock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies that MetadataFilterService pre-warms CooldownCache L1
 * with release dates extracted from metadata (H1).
 *
 * After metadata parse, versions older than the cooldown period
 * should be immediately available in L1 as allowed (false), without
 * requiring a DB/Valkey round-trip.
 *
 * @since 2.2.0
 */
final class CooldownCachePreWarmTest {

    private CooldownCache cooldownCache;
    private MetadataFilterService service;
    private AtomicInteger dbQueryCount;

    @BeforeEach
    void setUp() {
        this.cooldownCache = new CooldownCache(10_000, Duration.ofHours(24), null);
        this.dbQueryCount = new AtomicInteger(0);
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        this.service = new MetadataFilterService(
            new NoopTestCooldownService(),
            settings,
            this.cooldownCache,
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );
    }

    @Test
    void preWarmsL1WithOldReleaseDatesFromMetadata() throws Exception {
        // Versions: 1.0.0 released 30 days ago (old, should be pre-warmed as allowed),
        //           2.0.0 released 3 days ago (within cooldown, should NOT be pre-warmed)
        final Map<String, Instant> releaseDates = new HashMap<>();
        releaseDates.put("1.0.0", Instant.now().minus(Duration.ofDays(30)));
        releaseDates.put("2.0.0", Instant.now().minus(Duration.ofDays(3)));

        final PreWarmParser parser = new PreWarmParser(
            Arrays.asList("1.0.0", "2.0.0"), "2.0.0", releaseDates
        );
        final SimpleFilter filter = new SimpleFilter();
        final SimpleRewriter rewriter = new SimpleRewriter();

        // Run metadata filtering -- this should trigger pre-warming
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter,
            Optional.of(new NoopInspector())
        ).get();

        // Now check: looking up 1.0.0 in CooldownCache should be an L1 hit (pre-warmed)
        final CompletableFuture<Boolean> result = this.cooldownCache.isBlocked(
            "test-repo", "test-pkg", "1.0.0",
            () -> {
                this.dbQueryCount.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        );
        assertThat("1.0.0 should be allowed (pre-warmed from metadata)", result.get(), equalTo(false));
        assertThat("DB should NOT be queried for pre-warmed version", this.dbQueryCount.get(), equalTo(0));
    }

    @Test
    void doesNotPreWarmFreshVersions() throws Exception {
        // Version released 2 days ago -- within 7-day cooldown, should NOT be pre-warmed
        final Map<String, Instant> releaseDates = new HashMap<>();
        releaseDates.put("1.0.0", Instant.now().minus(Duration.ofDays(2)));

        final PreWarmParser parser = new PreWarmParser(
            Collections.singletonList("1.0.0"), "1.0.0", releaseDates
        );

        this.service.filterMetadata(
            "npm", "test-repo", "fresh-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, new SimpleFilter(), new SimpleRewriter(),
            Optional.of(new NoopInspector())
        ).get();

        // Looking up 1.0.0 should trigger a DB query (not pre-warmed)
        final CompletableFuture<Boolean> result = this.cooldownCache.isBlocked(
            "test-repo", "fresh-pkg", "1.0.0",
            () -> {
                this.dbQueryCount.incrementAndGet();
                return CompletableFuture.completedFuture(false);
            }
        );
        result.get();
        assertThat("DB should be queried for fresh version", this.dbQueryCount.get(), equalTo(1));
    }

    // -- test doubles -------------------------------------------------------

    /**
     * Parser that returns configurable release dates via extractReleaseDates().
     */
    private static final class PreWarmParser implements MetadataParser<List<String>> {
        private final List<String> versions;
        private final String latest;
        private final Map<String, Instant> releaseDates;

        PreWarmParser(
            final List<String> versions,
            final String latest,
            final Map<String, Instant> releaseDates
        ) {
            this.versions = versions;
            this.latest = latest;
            this.releaseDates = releaseDates;
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
            return Optional.ofNullable(this.latest);
        }

        @Override
        public String contentType() {
            return "application/json";
        }

        @Override
        public Map<String, Instant> extractReleaseDates(final List<String> metadata) {
            return this.releaseDates;
        }
    }

    private static final class SimpleFilter implements MetadataFilter<List<String>> {
        @Override
        public List<String> filter(final List<String> metadata, final Set<String> blocked) {
            return metadata.stream().filter(v -> !blocked.contains(v)).collect(Collectors.toList());
        }

        @Override
        public List<String> updateLatest(final List<String> metadata, final String latest) {
            return metadata;
        }
    }

    private static final class SimpleRewriter implements MetadataRewriter<List<String>> {
        @Override
        public byte[] rewrite(final List<String> metadata) {
            return String.join(",", metadata).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String contentType() {
            return "application/json";
        }
    }

    private static final class NoopInspector implements CooldownInspector {
        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<com.auto1.pantera.cooldown.api.CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    private static final class NoopTestCooldownService implements CooldownService {
        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            String t, String n, String a, String v, String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(String t, String n, String actor) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(String t, String n) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
