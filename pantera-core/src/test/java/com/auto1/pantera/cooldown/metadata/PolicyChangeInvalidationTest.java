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
import com.auto1.pantera.cooldown.api.CooldownReason;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tests that a cooldown policy change (e.g. duration 30d → 5d) via the
 * config API invalidates {@link FilteredMetadataCache} entries for the
 * affected repo type, and that the next query recomputes the result
 * against the new policy rather than returning stale filtered bytes.
 *
 * <p>The policy-change path in {@code CooldownHandler.updateConfig} does
 * two things when the cooldown duration changes:</p>
 * <ol>
 *   <li>{@code settings.update(...)} — mutate the live {@link CooldownSettings}
 *       so future evaluations use the new duration.</li>
 *   <li>{@code metadataService.clearAll()} — wipe every cached filtered
 *       metadata response, since the duration shift can flip a version's
 *       block decision.</li>
 * </ol>
 * This test exercises both steps and asserts the recomputed result
 * reflects the new duration.
 *
 * @since 1.0
 */
final class PolicyChangeInvalidationTest {

    /**
     * Package under test.
     */
    private static final String PKG = "test-pkg";

    private MetadataFilterService service;
    private PolicyAwareCooldownService cooldownService;
    private CooldownSettings settings;
    private CooldownCache cooldownCache;
    private FilteredMetadataCache metadataCache;

    @BeforeEach
    void setUp() {
        this.cooldownService = new PolicyAwareCooldownService();
        // Start with a 30-day cooldown window.
        this.settings = new CooldownSettings(true, Duration.ofDays(30));
        this.cooldownService.bindSettings(this.settings);
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
     * Scenario: Package has two versions.
     *   - 1.0.0 released 60 days ago (old)
     *   - 1.1.0 released 10 days ago (recent)
     * Initial duration X = 30d → 1.1.0 is blocked, result contains only 1.0.0.
     * Change duration Y = 5d via settings.update() + clearAll() → 1.1.0 is
     * no longer within the cooldown window and must appear in the fresh result.
     */
    @Test
    void policyShorteningInvalidatesCacheAndIncludesPreviouslyBlockedVersion() throws Exception {
        final Instant now = Instant.now();
        final Map<String, Instant> releaseDates = new HashMap<>();
        releaseDates.put("1.0.0", now.minus(Duration.ofDays(60)));
        releaseDates.put("1.1.0", now.minus(Duration.ofDays(10)));

        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "1.1.0"),
            "1.1.0",
            releaseDates
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector(releaseDates);

        // --- T1: duration = 30d → 1.1.0 (10 days old) is blocked ---
        final byte[] resultT1 = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat(
            "under 30d policy, 1.1.0 (10 days old) must be blocked",
            filter.lastBlockedVersions.contains("1.1.0"), equalTo(true)
        );
        assertThat(
            "result must NOT contain 1.1.0 under 30d policy",
            new String(resultT1, StandardCharsets.UTF_8).contains("1.1.0"), equalTo(false)
        );
        assertThat(
            "result must contain the older 1.0.0",
            new String(resultT1, StandardCharsets.UTF_8).contains("1.0.0"), equalTo(true)
        );

        final int parseCountAfterT1 = parser.parseCount;

        // --- Cache hit check: a second query with the OLD policy returns cached bytes ---
        this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();
        assertThat(
            "second query under same policy must hit the cache (no re-parse)",
            parser.parseCount, equalTo(parseCountAfterT1)
        );

        // --- Policy change via config API: shrink to 5d, mirror CooldownHandler.updateConfig ---
        this.settings.update(true, Duration.ofDays(5), Collections.emptyMap());
        this.service.clearAll();

        // --- T2: duration = 5d → 1.1.0 (10 days old) is past cooldown and must appear ---
        final byte[] resultT2 = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat(
            "after clearAll, the cached entry must have been invalidated and re-parsed",
            parser.parseCount, equalTo(parseCountAfterT1 + 1)
        );
        assertThat(
            "under 5d policy, 1.1.0 (10 days old) must NOT be blocked",
            filter.lastBlockedVersions.contains("1.1.0"), equalTo(false)
        );
        assertThat(
            "blocked versions set must be empty under the relaxed policy",
            filter.lastBlockedVersions.size(), equalTo(0)
        );
        final String bodyT2 = new String(resultT2, StandardCharsets.UTF_8);
        assertThat(
            "result must now contain 1.1.0 after policy shortening",
            bodyT2.contains("1.1.0"), equalTo(true)
        );
        assertThat(
            "result must still contain 1.0.0",
            bodyT2.contains("1.0.0"), equalTo(true)
        );
    }

    /**
     * Scenario: Policy lengthening — version that was allowed at X now blocked at Y.
     * Initial 5d → 1.1.0 (released 10d ago) is allowed.
     * Change to 30d → 1.1.0 must now be blocked; cache must be invalidated.
     */
    @Test
    void policyLengtheningInvalidatesCacheAndBlocksPreviouslyAllowedVersion() throws Exception {
        // Rebuild with 5d policy
        this.settings.update(true, Duration.ofDays(5), Collections.emptyMap());

        final Instant now = Instant.now();
        final Map<String, Instant> releaseDates = new HashMap<>();
        releaseDates.put("1.0.0", now.minus(Duration.ofDays(60)));
        releaseDates.put("1.1.0", now.minus(Duration.ofDays(10)));

        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "1.1.0"),
            "1.1.0",
            releaseDates
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector(releaseDates);

        final byte[] resultT1 = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();
        assertThat(
            "under 5d policy, 1.1.0 (10d old) is allowed",
            filter.lastBlockedVersions.contains("1.1.0"), equalTo(false)
        );
        assertThat(
            "result contains 1.1.0 under 5d policy",
            new String(resultT1, StandardCharsets.UTF_8).contains("1.1.0"), equalTo(true)
        );

        final int parseCountAfterT1 = parser.parseCount;

        // Policy change: lengthen to 30d, invalidate all.
        this.settings.update(true, Duration.ofDays(30), Collections.emptyMap());
        this.service.clearAll();

        final byte[] resultT2 = this.service.filterMetadata(
            "npm", "test-repo", PKG,
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat(
            "clearAll forced a re-parse under the new policy",
            parser.parseCount, equalTo(parseCountAfterT1 + 1)
        );
        assertThat(
            "under 30d policy, 1.1.0 (10d old) must be blocked",
            filter.lastBlockedVersions.contains("1.1.0"), equalTo(true)
        );
        assertThat(
            "result no longer contains 1.1.0 after policy lengthening",
            new String(resultT2, StandardCharsets.UTF_8).contains("1.1.0"), equalTo(false)
        );
    }

    // --- Fakes ---

    /**
     * Cooldown service whose block decision is derived from the live
     * {@link CooldownSettings} — mimics production behavior where a
     * policy change immediately affects future evaluations.
     * A version is blocked iff its release date is within
     * {@code settings.minimumAllowedAgeFor(repoType)} from now.
     */
    private static final class PolicyAwareCooldownService implements CooldownService {
        private final Map<String, Instant> releaseDates = new HashMap<>();
        private CooldownSettings bound;

        void bindSettings(final CooldownSettings settings) {
            this.bound = settings;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request,
            final CooldownInspector inspector
        ) {
            return inspector.releaseDate(request.artifact(), request.version())
                .thenApply(maybeDate -> {
                    if (maybeDate.isEmpty()) {
                        return CooldownResult.allowed();
                    }
                    final Duration window = this.bound.minimumAllowedAgeFor(request.repoType());
                    final Instant cutoff = Instant.now().minus(window);
                    if (maybeDate.get().isAfter(cutoff)) {
                        // Released within the cooldown window → blocked.
                        final Instant blockedUntil = maybeDate.get().plus(window);
                        return CooldownResult.blocked(new CooldownBlock(
                            request.repoType(),
                            request.repoName(),
                            request.artifact(),
                            request.version(),
                            CooldownReason.FRESH_RELEASE,
                            Instant.now(),
                            blockedUntil,
                            Collections.emptyList()
                        ));
                    }
                    return CooldownResult.allowed();
                });
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

    private static final class TestMetadataParser implements MetadataParser<List<String>> {
        private final List<String> versions;
        private final String latest;
        private final Map<String, Instant> releaseDates;
        int parseCount = 0;

        TestMetadataParser(
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
            this.parseCount++;
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
        public Map<String, Instant> extractReleaseDates(final List<String> metadata) {
            return new HashMap<>(this.releaseDates);
        }

        @Override
        public String contentType() {
            return "application/json";
        }
    }

    private static final class TestMetadataFilter implements MetadataFilter<List<String>> {
        Set<String> lastBlockedVersions = new java.util.HashSet<>();
        String lastNewLatest;

        @Override
        public List<String> filter(final List<String> metadata, final Set<String> blockedVersions) {
            this.lastBlockedVersions = blockedVersions;
            return metadata.stream()
                .filter(v -> !blockedVersions.contains(v))
                .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public List<String> updateLatest(final List<String> metadata, final String newLatest) {
            this.lastNewLatest = newLatest;
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

    private static final class TestCooldownInspector implements CooldownInspector {
        private final Map<String, Instant> releaseDates;

        TestCooldownInspector(final Map<String, Instant> releaseDates) {
            this.releaseDates = new HashMap<>(releaseDates);
        }

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(
                Optional.ofNullable(this.releaseDates.get(version))
            );
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
