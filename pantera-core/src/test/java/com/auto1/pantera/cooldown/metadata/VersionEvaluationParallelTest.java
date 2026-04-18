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
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

/**
 * Verifies that version evaluation runs in parallel (H2).
 * 50 versions, all L1-cached, should complete well under 50 ms.
 *
 * @since 2.2.0
 */
final class VersionEvaluationParallelTest {

    @Test
    void fiftyVersionsAllCachedCompletesUnder50ms() throws Exception {
        final int versionCount = 50;
        final List<String> versions = new ArrayList<>(versionCount);
        for (int i = 0; i < versionCount; i++) {
            versions.add(String.format("1.0.%d", i));
        }

        final CooldownCache cooldownCache = new CooldownCache(10_000, Duration.ofHours(24), null);
        // Pre-warm all versions as allowed in L1
        for (final String version : versions) {
            cooldownCache.put("test-repo", "test-pkg", version, false);
        }

        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        final MetadataFilterService service = new MetadataFilterService(
            new FastCooldownService(),
            settings,
            cooldownCache,
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            versionCount
        );

        final SimpleParser parser = new SimpleParser(versions, versions.get(versions.size() - 1));
        final SimpleFilter filter = new SimpleFilter();
        final SimpleRewriter rewriter = new SimpleRewriter();
        final SimpleInspector inspector = new SimpleInspector();

        // Warm up JIT
        service.filterMetadata(
            "npm", "test-repo", "warmup-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        // Invalidate the warmup entry
        service.invalidate("npm", "test-repo", "warmup-pkg");

        // Timed run
        final long start = System.nanoTime();
        service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(
            String.format("50-version evaluation took %d ms, expected < 50 ms", elapsedMs),
            elapsedMs, lessThan(50L)
        );
    }

    // -- test doubles -------------------------------------------------------

    private static final class FastCooldownService implements CooldownService {
        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(String t, String n, String a, String v, String actor) {
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

    private static final class SimpleParser implements MetadataParser<List<String>> {
        private final List<String> versions;
        private final String latest;

        SimpleParser(final List<String> versions, final String latest) {
            this.versions = versions;
            this.latest = latest;
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

    private static final class SimpleInspector implements CooldownInspector {
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
}
