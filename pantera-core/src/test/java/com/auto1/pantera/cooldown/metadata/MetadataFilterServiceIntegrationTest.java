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

import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration test for {@link MetadataFilterService} end-to-end with
 * Go adapter components (simplest metadata format: plain-text version list).
 *
 * <p>Wires MetadataFilterService with a fake CooldownService that blocks
 * versions {@code v1.0.0} and {@code v2.0.0}. Verifies:</p>
 * <ul>
 *   <li>Filtered bytes don't contain blocked versions</li>
 *   <li>Cache hit on second call (verify via parse count)</li>
 *   <li>SWR behaviour: expire, get stale, background re-evaluates</li>
 * </ul>
 *
 * @since 2.2.0
 */
final class MetadataFilterServiceIntegrationTest {

    /**
     * Go-style metadata: one version per line.
     */
    private static final String GO_METADATA =
        "v0.1.0\nv0.2.0\nv1.0.0\nv1.1.0\nv2.0.0\nv2.1.0\nv3.0.0";

    private BlockingCooldownService cooldownService;
    private CooldownSettings settings;
    private CooldownCache cooldownCache;
    private FilteredMetadataCache metadataCache;
    private MetadataFilterService service;

    @BeforeEach
    void setUp() {
        this.cooldownService = new BlockingCooldownService();
        this.cooldownService.block("test-pkg", "v1.0.0");
        this.cooldownService.block("test-pkg", "v2.0.0");

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

    @Test
    void filteredBytesDoNotContainBlockedVersions() throws Exception {
        final GoParser parser = new GoParser();
        final GoFilter filter = new GoFilter();
        final GoRewriter rewriter = new GoRewriter();

        final byte[] result = this.service.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter,
            Optional.of(new NoopInspector())
        ).get();

        final String output = new String(result, StandardCharsets.UTF_8);
        assertThat("v1.0.0 must be filtered out", output, not(containsString("v1.0.0")));
        assertThat("v2.0.0 must be filtered out", output, not(containsString("v2.0.0")));
        assertThat("v0.1.0 must remain", output, containsString("v0.1.0"));
        assertThat("v0.2.0 must remain", output, containsString("v0.2.0"));
        assertThat("v1.1.0 must remain", output, containsString("v1.1.0"));
        assertThat("v2.1.0 must remain", output, containsString("v2.1.0"));
        assertThat("v3.0.0 must remain", output, containsString("v3.0.0"));
    }

    @Test
    void cacheHitOnSecondCall() throws Exception {
        final GoParser parser = new GoParser();
        final GoFilter filter = new GoFilter();
        final GoRewriter rewriter = new GoRewriter();
        final NoopInspector inspector = new NoopInspector();

        // First call — cache miss
        this.service.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        final int firstParseCount = parser.parseCount;
        assertThat("First call must parse", firstParseCount, greaterThanOrEqualTo(1));

        // Second call — cache hit
        this.service.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat("Second call must hit cache (no re-parse)",
            parser.parseCount, equalTo(firstParseCount));
    }

    @Test
    void swrReturnsStaleAndRevalidatesInBackground() throws Exception {
        // Use a very short blockedUntil so the cache entry expires quickly
        final Instant shortBlockedUntil = Instant.now().plus(Duration.ofMillis(100));
        final ShortExpiryCooldownService shortService =
            new ShortExpiryCooldownService(shortBlockedUntil);
        shortService.block("test-pkg", "v1.0.0");

        final MetadataFilterService swrService = new MetadataFilterService(
            shortService,
            this.settings,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );

        final GoParser parser = new GoParser();
        final GoFilter filter = new GoFilter();
        final GoRewriter rewriter = new GoRewriter();
        final NoopInspector inspector = new NoopInspector();

        // First call — caches with short TTL
        final byte[] result1 = swrService.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        final String output1 = new String(result1, StandardCharsets.UTF_8);
        assertThat("v1.0.0 should be blocked initially", output1, not(containsString("v1.0.0")));
        final int firstParseCount = parser.parseCount;

        // Wait for logical expiry
        Thread.sleep(150);

        // Expire the block in the service
        shortService.unblock("go", "go-repo", "test-pkg", "v1.0.0", "admin");

        // Second call — SWR returns stale bytes immediately
        final byte[] result2 = swrService.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        // Stale response is served immediately (may still have v1.0.0 filtered)
        assertThat("SWR returns bytes", result2.length > 0, equalTo(true));

        // Wait for background revalidation
        Thread.sleep(300);

        // Third call — should return fresh data
        swrService.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        // Parser should have been called again by background revalidation
        assertThat("Background revalidation should re-parse",
            parser.parseCount, greaterThanOrEqualTo(firstParseCount + 1));
    }

    @Test
    void invalidateForcesCacheMiss() throws Exception {
        final GoParser parser = new GoParser();
        final GoFilter filter = new GoFilter();
        final GoRewriter rewriter = new GoRewriter();
        final NoopInspector inspector = new NoopInspector();

        // Populate cache
        this.service.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();
        final int count1 = parser.parseCount;

        // Invalidate
        this.service.invalidate("go", "go-repo", "test-pkg");

        // Next call must re-parse
        this.service.filterMetadata(
            "go", "go-repo", "test-pkg",
            GO_METADATA.getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat("Post-invalidation must re-parse",
            parser.parseCount, equalTo(count1 + 1));
    }

    @Test
    void disabledCooldownReturnsRawMetadata() throws Exception {
        final CooldownSettings disabled = new CooldownSettings(false, Duration.ofDays(7));
        final MetadataFilterService disabledService = new MetadataFilterService(
            this.cooldownService, disabled, this.cooldownCache,
            this.metadataCache, ForkJoinPool.commonPool(), 50
        );

        final byte[] raw = GO_METADATA.getBytes(StandardCharsets.UTF_8);
        final byte[] result = disabledService.filterMetadata(
            "go", "go-repo", "test-pkg", raw,
            new GoParser(), new GoFilter(), new GoRewriter(),
            Optional.empty()
        ).get();

        assertThat("Disabled cooldown returns raw bytes", result, equalTo(raw));
    }

    // --- Minimal Go adapter implementations for integration testing ---

    private static final class GoParser implements MetadataParser<List<String>> {
        volatile int parseCount;

        @Override
        public List<String> parse(final byte[] bytes) {
            this.parseCount++;
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
        public List<String> filter(final List<String> metadata, final Set<String> blockedVersions) {
            if (blockedVersions.isEmpty()) {
                return metadata;
            }
            final List<String> result = new ArrayList<>();
            for (final String v : metadata) {
                if (!blockedVersions.contains(v)) {
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

    // --- Test doubles ---

    private static final class BlockingCooldownService implements CooldownService {
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

    private static final class ShortExpiryCooldownService implements CooldownService {
        private final Set<String> blocked = new HashSet<>();
        private final Instant blockedUntil;

        ShortExpiryCooldownService(final Instant blockedUntil) {
            this.blockedUntil = blockedUntil;
        }

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
                        this.blockedUntil, Collections.emptyList()
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
