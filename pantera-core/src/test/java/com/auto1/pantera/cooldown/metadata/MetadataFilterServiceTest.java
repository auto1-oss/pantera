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
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.PublishDateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link MetadataFilterService}.
 *
 * @since 1.0
 */
final class MetadataFilterServiceTest {

    private MetadataFilterService service;
    private TestCooldownService cooldownService;
    private CooldownSettings settings;
    private CooldownCache cooldownCache;
    private FilteredMetadataCache metadataCache;
    private PublishDateRegistry previousRegistry;

    @BeforeEach
    void setUp() {
        this.cooldownService = new TestCooldownService();
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
        this.previousRegistry = PublishDateRegistries.instance();
    }

    @AfterEach
    void tearDown() {
        // Restore the previous registry so subsequent tests in this class — and
        // anywhere else in the JVM that shares the static PublishDateRegistries
        // singleton — see the original instance, not a per-test fake.
        if (this.previousRegistry != null) {
            PublishDateRegistries.installDefault(this.previousRegistry);
        }
    }

    @Test
    void filtersBlockedVersions() throws Exception {
        // Setup: version 3.0.0 is blocked
        this.cooldownService.blockVersion("test-pkg", "3.0.0");

        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0", "3.0.0"),
            "3.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        final byte[] result = this.service.filterMetadata(
            "npm",
            "test-repo",
            "test-pkg",
            "raw-metadata".getBytes(StandardCharsets.UTF_8),
            parser,
            filter,
            rewriter
        ).get();

        // Verify blocked version was filtered
        assertThat(filter.lastBlockedVersions.contains("3.0.0"), equalTo(true));
        // Verify latest was updated (3.0.0 was latest but blocked)
        assertThat(filter.lastNewLatest, equalTo("2.0.0"));
    }

    @Test
    void allowsAllVersionsWhenNoneBlocked() throws Exception {
        // No versions blocked
        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0", "3.0.0"),
            "3.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        this.service.filterMetadata(
            "npm",
            "test-repo",
            "test-pkg",
            "raw-metadata".getBytes(StandardCharsets.UTF_8),
            parser,
            filter,
            rewriter
        ).get();

        // No versions should be blocked
        assertThat(filter.lastBlockedVersions.isEmpty(), equalTo(true));
        // Latest should not be updated
        assertThat(filter.lastNewLatest, equalTo(null));
    }

    @Test
    void throwsWhenAllVersionsBlocked() {
        // Block all versions
        this.cooldownService.blockVersion("test-pkg", "1.0.0");
        this.cooldownService.blockVersion("test-pkg", "2.0.0");
        this.cooldownService.blockVersion("test-pkg", "3.0.0");

        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0", "3.0.0"),
            "3.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        final ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> this.service.filterMetadata(
                "npm",
                "test-repo",
                "test-pkg",
                "raw-metadata".getBytes(StandardCharsets.UTF_8),
                parser,
                filter,
                rewriter
            ).get()
        );

        assertThat(exception.getCause() instanceof AllVersionsBlockedException, equalTo(true));
        final AllVersionsBlockedException cause = (AllVersionsBlockedException) exception.getCause();
        assertThat(cause.packageName(), equalTo("test-pkg"));
        assertThat(cause.blockedVersions().size(), equalTo(3));
    }

    @Test
    void returnsRawMetadataWhenCooldownDisabled() throws Exception {
        // Disable cooldown
        final CooldownSettings disabledSettings = new CooldownSettings(false, Duration.ofDays(7));
        final MetadataFilterService disabledService = new MetadataFilterService(
            this.cooldownService,
            disabledSettings,
            this.cooldownCache,
            this.metadataCache,
            ForkJoinPool.commonPool(),
            50
        );

        final byte[] rawMetadata = "raw-metadata".getBytes(StandardCharsets.UTF_8);
        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0"),
            "2.0.0"
        );

        final byte[] result = disabledService.filterMetadata(
            "npm",
            "test-repo",
            "test-pkg",
            rawMetadata,
            parser,
            new TestMetadataFilter(),
            new TestMetadataRewriter()
        ).get();

        // Should return raw metadata unchanged
        assertThat(result, equalTo(rawMetadata));
    }

    /**
     * Regression: EVERY metadata listing view must emit exactly one
     * {@code artifact_resolution} audit record — including repeat requests
     * served from the filtered-metadata cache. Before this fix the audit
     * fired only on the compute path, so "npm show" #2..N (cache hits)
     * were an audit blackout.
     */
    @Test
    void auditsResolutionOnEveryCallIncludingCacheHits() throws Exception {
        final AuditCapture capture = AuditCapture.install();
        try {
            this.cooldownService.blockVersion("test-pkg", "3.0.0");
            final byte[] raw = "raw-metadata".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 3; i = i + 1) {
                this.service.filterMetadata(
                    "npm", "test-repo", "test-pkg", raw,
                    new TestMetadataParser(Arrays.asList("1.0.0", "2.0.0", "3.0.0"), "3.0.0"),
                    new TestMetadataFilter(), new TestMetadataRewriter(),
                    com.auto1.pantera.audit.AuditContext.NONE, "alice"
                ).get();
            }
            final List<org.apache.logging.log4j.core.LogEvent> events =
                capture.resolutionEvents();
            assertThat(
                "three metadata views (1 compute + 2 cache hits) -> three resolution records",
                events.size(), equalTo(3)
            );
            for (final org.apache.logging.log4j.core.LogEvent event : events) {
                assertThat(
                    "every record names the blocked version",
                    AuditCapture.field(event, "message").toString(),
                    containsString("3.0.0")
                );
            }
        } finally {
            capture.remove();
        }
    }

    /**
     * Regression: cooldown disabled for the repo is "nothing filtered",
     * not "nothing to audit" — the listing view must still be recorded.
     */
    @Test
    void auditsResolutionWhenCooldownDisabled() throws Exception {
        final AuditCapture capture = AuditCapture.install();
        try {
            final MetadataFilterService disabledService = new MetadataFilterService(
                this.cooldownService,
                new CooldownSettings(false, Duration.ofDays(7)),
                this.cooldownCache,
                this.metadataCache,
                ForkJoinPool.commonPool(),
                50
            );
            final byte[] raw = "raw-metadata".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 2; i = i + 1) {
                disabledService.filterMetadata(
                    "npm", "test-repo", "disabled-pkg", raw,
                    new TestMetadataParser(Arrays.asList("1.0.0"), "1.0.0"),
                    new TestMetadataFilter(), new TestMetadataRewriter(),
                    com.auto1.pantera.audit.AuditContext.NONE, "alice"
                ).get();
            }
            final List<org.apache.logging.log4j.core.LogEvent> events =
                capture.resolutionEvents();
            assertThat(
                "cooldown-disabled metadata views are still audited, one per request",
                events.size(), equalTo(2)
            );
            assertThat(
                AuditCapture.field(events.get(0), "message").toString(),
                containsString("no cooldown filtering applied")
            );
        } finally {
            capture.remove();
        }
    }

    /**
     * Regression: the all-versions-blocked branch throws to the caller
     * (mapped to 403/404) but is still a metadata request — it must be
     * audited with the full blocked list.
     */
    @Test
    void auditsResolutionWhenAllVersionsBlocked() {
        final AuditCapture capture = AuditCapture.install();
        try {
            this.cooldownService.blockVersion("all-blocked-pkg", "1.0.0");
            this.cooldownService.blockVersion("all-blocked-pkg", "2.0.0");
            assertThrows(
                ExecutionException.class,
                () -> this.service.filterMetadata(
                    "npm", "test-repo", "all-blocked-pkg",
                    "raw-metadata".getBytes(StandardCharsets.UTF_8),
                    new TestMetadataParser(Arrays.asList("1.0.0", "2.0.0"), "2.0.0"),
                    new TestMetadataFilter(), new TestMetadataRewriter(),
                    com.auto1.pantera.audit.AuditContext.NONE, "alice"
                ).get()
            );
            final List<org.apache.logging.log4j.core.LogEvent> events =
                capture.resolutionEvents();
            assertThat(
                "all-blocked denial still produces a resolution record",
                events.size(), equalTo(1)
            );
            assertThat(
                "record lists the hidden versions",
                AuditCapture.field(events.get(0), "message").toString(),
                containsString("2 version(s) filtered")
            );
        } finally {
            capture.remove();
        }
    }

    @Test
    void cachesFilteredMetadata() throws Exception {
        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0"),
            "2.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        // First call - should process
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        final int firstParseCount = parser.parseCount;

        // Second call - should hit cache
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        // Parse count should not increase (cache hit)
        assertThat(parser.parseCount, equalTo(firstParseCount));
    }

    @Test
    void invalidatesCacheCorrectly() throws Exception {
        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0"),
            "2.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        // First call
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        final int firstParseCount = parser.parseCount;

        // Invalidate
        this.service.invalidate("npm", "test-repo", "test-pkg");

        // Third call - should reprocess after invalidation
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        // Parse count should increase (cache miss after invalidation)
        assertThat(parser.parseCount, equalTo(firstParseCount + 1));
    }

    @Test
    void statsReportsCorrectly() {
        final String stats = this.service.stats();
        assertThat(stats, containsString("FilteredMetadataCache"));
    }

    @Test
    void unblockInvalidatesCacheAndIncludesPreviouslyBlockedVersion() throws Exception {
        // Setup: version 3.0.0 is blocked
        this.cooldownService.blockVersion("test-pkg", "3.0.0");

        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0", "3.0.0"),
            "3.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        // First request - 3.0.0 should be filtered out
        final byte[] result1 = this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw-metadata".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        assertThat("3.0.0 should be blocked", filter.lastBlockedVersions.contains("3.0.0"), equalTo(true));
        assertThat("Result should not contain 3.0.0", 
            new String(result1, StandardCharsets.UTF_8).contains("3.0.0"), equalTo(false));

        final int firstParseCount = parser.parseCount;

        // Simulate unblock: remove from blocked set and invalidate cache
        this.cooldownService.unblock("npm", "test-repo", "test-pkg", "3.0.0", "admin");
        this.service.invalidate("npm", "test-repo", "test-pkg");

        // Second request - 3.0.0 should now be included
        final byte[] result2 = this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw-metadata".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        // Should have re-parsed (cache was invalidated)
        assertThat("Should re-parse after invalidation", parser.parseCount, equalTo(firstParseCount + 1));
        // 3.0.0 should no longer be blocked
        assertThat("3.0.0 should not be blocked after unblock", 
            filter.lastBlockedVersions.contains("3.0.0"), equalTo(false));
        // Result should now contain 3.0.0
        assertThat("Result should contain 3.0.0 after unblock", 
            new String(result2, StandardCharsets.UTF_8).contains("3.0.0"), equalTo(true));
    }

    @Test
    void unblockAllInvalidatesAllPackagesInRepo() throws Exception {
        // Block versions in multiple packages
        this.cooldownService.blockVersion("pkg1", "1.0.0");
        this.cooldownService.blockVersion("pkg2", "2.0.0");

        final TestMetadataParser parser1 = new TestMetadataParser(
            Arrays.asList("1.0.0", "1.1.0"), "1.1.0"
        );
        final TestMetadataParser parser2 = new TestMetadataParser(
            Arrays.asList("2.0.0", "2.1.0"), "2.1.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        // Load both packages into cache
        this.service.filterMetadata(
            "npm", "test-repo", "pkg1",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser1, filter, rewriter
        ).get();

        this.service.filterMetadata(
            "npm", "test-repo", "pkg2",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser2, filter, rewriter
        ).get();

        final int parseCount1 = parser1.parseCount;
        final int parseCount2 = parser2.parseCount;

        // Simulate unblockAll: clear all blocks and invalidate all cache
        this.cooldownService.unblockAll("npm", "test-repo", "admin");
        this.service.invalidateAll("npm", "test-repo");

        // Both packages should reload
        this.service.filterMetadata(
            "npm", "test-repo", "pkg1",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser1, filter, rewriter
        ).get();

        this.service.filterMetadata(
            "npm", "test-repo", "pkg2",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser2, filter, rewriter
        ).get();

        assertThat("pkg1 should re-parse after invalidateAll", 
            parser1.parseCount, equalTo(parseCount1 + 1));
        assertThat("pkg2 should re-parse after invalidateAll", 
            parser2.parseCount, equalTo(parseCount2 + 1));
    }

    @Test
    void cacheExpiresWhenBlockExpiresAndReturnsUnblockedVersion() throws Exception {
        // Block version with very short expiry (100ms)
        final Instant shortBlockedUntil = Instant.now().plus(Duration.ofMillis(100));

        // Use a custom cooldown service that returns short blockedUntil
        final ShortExpiryTestCooldownService shortExpiryService =
            new ShortExpiryTestCooldownService(shortBlockedUntil);
        shortExpiryService.blockVersion("test-pkg", "3.0.0");

        final MetadataFilterService shortExpiryMetadataService = new MetadataFilterService(
            shortExpiryService,
            this.settings,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );

        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0", "3.0.0"),
            "3.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final TestCooldownInspector inspector = new TestCooldownInspector();

        // First request - 3.0.0 should be blocked
        shortExpiryMetadataService.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        assertThat("3.0.0 should be blocked initially",
            filter.lastBlockedVersions.contains("3.0.0"), equalTo(true));

        final int firstParseCount = parser.parseCount;

        // Wait for block to expire
        Thread.sleep(150);

        // Simulate block expiry in cooldown service
        shortExpiryService.expireBlock("test-pkg", "3.0.0");

        // Second request after expiry — SWR returns stale bytes immediately
        // and triggers background re-evaluation. The stale response still has
        // 3.0.0 filtered, but the background revalidation runs asynchronously.
        shortExpiryMetadataService.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        // Wait for background revalidation to complete
        Thread.sleep(200);

        // Third request — should return fresh data with 3.0.0 allowed
        shortExpiryMetadataService.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();

        // Background revalidation should have re-parsed
        assertThat("Should re-parse via SWR background revalidation",
            parser.parseCount, equalTo(firstParseCount + 1));
        // 3.0.0 should no longer be blocked after revalidation
        assertThat("3.0.0 should not be blocked after expiry + revalidation",
            filter.lastBlockedVersions.contains("3.0.0"), equalTo(false));
    }

    @Test
    void updateLatestFiresWhenLatestSurvivesButOtherVersionIsBlocked()
        throws ExecutionException, InterruptedException {
        // Phase D: pre-change, updateLatest only ran when <latest> itself was
        // blocked — leaving <release> stale. Post-change, the rewriter must
        // fire on ANY block so <release> gets recomputed to the newest
        // surviving stable. Here <latest>=X-SNAPSHOT survives, 9.1.0 (stable)
        // is blocked, and we assert updateLatest still ran.
        this.cooldownService.blockVersion("test-pkg", "9.1.0");
        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("8.0.0", "9.0.0", "9.1.0", "X-SNAPSHOT"),
            "X-SNAPSHOT"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        this.service.filterMetadata(
            "maven", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();
        assertThat(
            "9.1.0 blocked", filter.lastBlockedVersions.contains("9.1.0"), equalTo(true)
        );
        assertThat(
            "updateLatest must fire whenever any version is blocked",
            filter.lastNewLatest != null, equalTo(true)
        );
    }

    @Test
    void isPrereleaseRecognisesStandardQualifiers() {
        assertThat(MetadataFilterService.isPrerelease("1.0.0-alpha"), is(true));
        assertThat(MetadataFilterService.isPrerelease("1.0.0-beta.2"), is(true));
        assertThat(MetadataFilterService.isPrerelease("2.5.0-rc1"), is(true));
        assertThat(MetadataFilterService.isPrerelease("2.5.0-RC2"), is(true));
        assertThat(MetadataFilterService.isPrerelease("3.0.0-SNAPSHOT"), is(true));
        assertThat(MetadataFilterService.isPrerelease("4.1.0-canary"), is(true));
        assertThat(MetadataFilterService.isPrerelease("4.1.0-next.5"), is(true));
        assertThat(MetadataFilterService.isPrerelease("1.0.0-pre"), is(true));
        assertThat(MetadataFilterService.isPrerelease("1.0-M3"), is(true));
        assertThat(MetadataFilterService.isPrerelease("1.0-milestone"), is(true));
    }

    @Test
    void isPrereleaseTreatsClassifierSuffixesAsStable() {
        // The Guava 33.x → r09 regression: "-jre"/"-android" are classifier
        // suffixes, not prerelease qualifiers. Misclassifying them collapses
        // the "newest stable" pick to a decade-old release.
        assertThat(MetadataFilterService.isPrerelease("33.5.0-jre"), is(false));
        assertThat(MetadataFilterService.isPrerelease("33.6.0-android"), is(false));
        assertThat(MetadataFilterService.isPrerelease("2.5.0.RELEASE"), is(false));
        // Spring style: legitimate suffix that historically tripped the
        // contains("rc") check (the substring "RELEASE" contains "EA"/"RE",
        // not "rc", but the principle is the same — token-based, not
        // substring-based).
        assertThat(MetadataFilterService.isPrerelease("2.5.0-RELEASE"), is(false));
    }

    @Test
    void isPrereleaseDoesNotMatchSubstringInsideUnrelatedWords() {
        // Guard against the contains("rc") / contains("dev") regression:
        // "archived" contains "rc"; "developer" contains "dev"; etc.
        assertThat(MetadataFilterService.isPrerelease("1.0.0-archived"), is(false));
        assertThat(MetadataFilterService.isPrerelease("1.0.0-developer"), is(false));
        assertThat(MetadataFilterService.isPrerelease("1.0.0-macos"), is(false));
        assertThat(MetadataFilterService.isPrerelease("1.0.0-betaflight"), is(false));
    }

    @Test
    void isPrereleaseHandlesPlainStableVersions() {
        assertThat(MetadataFilterService.isPrerelease("1.0.0"), is(false));
        assertThat(MetadataFilterService.isPrerelease("33.5.0"), is(false));
        assertThat(MetadataFilterService.isPrerelease("r09"), is(false));
        assertThat(MetadataFilterService.isPrerelease(""), is(false));
        assertThat(MetadataFilterService.isPrerelease(null), is(false));
    }

    @Test
    void perTypeLaxerOverrideExtendsMetadataFilterCutoff() throws Exception {
        // Bug regression: global=30d, per-type=60d (laxer per-type), one
        // version aged 40d (between the two cutoffs). Pre-fix, the metadata
        // filter used the global 30d cutoff for pre-selection, so the 40d
        // version was treated as "old enough — skip" and never reached the
        // request-time evaluator — silently allowed even though the per-type
        // 60d rule says it's too fresh.
        // Post-fix, the filter uses CooldownSettings.effectiveMinimumAllowedAge
        // which honours the per-type override; the 40d version is included in
        // versionsToEvaluate and the DateAwareCooldownService (configured with
        // 60d) blocks it.
        final CooldownSettings perTypeLaxer = new CooldownSettings(
            true,
            Duration.ofDays(30),
            Map.of("maven", new CooldownSettings.RepoTypeConfig(true, Duration.ofDays(60)))
        );
        final java.time.Instant fortyDaysAgo = java.time.Instant.now()
            .minus(java.time.Duration.ofDays(40));
        final java.time.Instant oneYearAgo = java.time.Instant.now()
            .minus(java.time.Duration.ofDays(365));
        final java.util.Map<String, java.time.Instant> registryDates = new java.util.HashMap<>();
        registryDates.put("1.2.0", fortyDaysAgo);
        registryDates.put("1.1.0", oneYearAgo);
        PublishDateRegistries.installDefault(new FakePublishDateRegistry(registryDates));
        final DateAwareCooldownService dateAware = new DateAwareCooldownService(
            Duration.ofDays(60)
        );
        final MetadataFilterService perTypeService = new MetadataFilterService(
            dateAware,
            perTypeLaxer,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );
        final List<String> versions = Arrays.asList("1.1.0", "1.2.0");
        final TestMetadataParser parser = new TestMetadataParser(versions, "1.2.0");
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        perTypeService.filterMetadata(
            "maven", "test-repo", "com.example.lib",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();
        assertThat(
            "40d version must be blocked under the per-type 60d rule",
            filter.lastBlockedVersions.contains("1.2.0"), equalTo(true)
        );
        assertThat(
            "1-year-old version stays allowed",
            filter.lastBlockedVersions.contains("1.1.0"), equalTo(false)
        );
    }

    @Test
    void perNameOverrideBeatsPerTypeInMetadataFilter() throws Exception {
        // Same precedence chain seen by JdbcCooldownService: per-name disables
        // cooldown for a single repo even when its repo-type override would
        // enable it. Verifies effectiveEnabled wiring through Site A.
        final CooldownSettings settings = new CooldownSettings(
            true,
            Duration.ofDays(30),
            Map.of("maven", new CooldownSettings.RepoTypeConfig(true, Duration.ofDays(60)))
        );
        settings.setRepoNameOverride("test-repo", false, Duration.ofDays(60));
        final MetadataFilterService perNameService = new MetadataFilterService(
            this.cooldownService,
            settings,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );
        this.cooldownService.blockVersion("test-pkg", "3.0.0");
        final TestMetadataParser parser = new TestMetadataParser(
            Arrays.asList("1.0.0", "2.0.0", "3.0.0"),
            "3.0.0"
        );
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        final byte[] raw = "raw".getBytes(StandardCharsets.UTF_8);
        final byte[] result = perNameService.filterMetadata(
            "maven", "test-repo", "test-pkg",
            raw, parser, filter, rewriter
        ).get();
        assertThat(
            "per-name override disabled — filter must return raw metadata unchanged",
            result, equalTo(raw)
        );
    }

    @Test
    void mavenMetadataBlocksVersionsViaPublishDateRegistry() throws Exception {
        // Regression: MavenMetadataParser.extractReleaseDates returns an empty
        // map (no per-version timestamps in artifact-level maven-metadata.xml).
        // Without registry backfill, the cooldown filter receives empty dates
        // for every version and shouldBlockNewArtifact fail-opens. The
        // PublishDateRegistries-backed backfill is what makes the Maven /
        // Gradle filter actually block.
        final java.time.Instant fresh = java.time.Instant.now()
            .minus(java.time.Duration.ofDays(2));
        final java.util.Map<String, java.time.Instant> registryDates = new java.util.HashMap<>();
        registryDates.put("33.6.0-jre", fresh);
        PublishDateRegistries.installDefault(new FakePublishDateRegistry(registryDates));
        final DateAwareCooldownService dateAware = new DateAwareCooldownService(
            this.settings.minimumAllowedAge()
        );
        final MetadataFilterService dateAwareService = new MetadataFilterService(
            dateAware,
            this.settings,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );
        final java.util.List<String> versions = java.util.Arrays.asList(
            "33.2.0-jre", "33.3.0-jre", "33.4.0-jre", "33.5.0-jre", "33.6.0-jre"
        );
        // Parser deliberately returns an empty inline date map — mirrors the
        // structural emptiness of MavenMetadataParser.extractReleaseDates.
        final TestMetadataParser parser = new TestMetadataParser(versions, "33.6.0-jre");
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        dateAwareService.filterMetadata(
            "maven", "test-repo", "com.google.guava.guava",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();
        assertThat(
            "33.6.0-jre must be blocked via registry-resolved date",
            filter.lastBlockedVersions.contains("33.6.0-jre"), equalTo(true)
        );
        assertThat(
            "Older versions without registry dates must not be blocked",
            filter.lastBlockedVersions.contains("33.5.0-jre"), equalTo(false)
        );
        assertThat(
            "Older versions without registry dates must not be blocked",
            filter.lastBlockedVersions.size(), equalTo(1)
        );
    }

    @Test
    void registryBackfillTargetsNewestVersionsBeyondCap() throws Exception {
        // Regression: pre-fix, backfill iterated allVersions in document order
        // and capped at maxVersionsToEvaluate. For Maven, document order is
        // ASCENDING, so the cap selected the OLDEST 50 versions to query the
        // publish-date registry; the newest versions — the only ones plausibly
        // inside the cooldown window — never got a date and silently passed
        // through. Fixture: 100 ascending versions, only the LAST one ("9.9.9")
        // has a recent date in the registry. With cap = 50 and ascending
        // document order, a naive backfill queries versions [0..49] and never
        // reaches index 99. The fix sorts {@code missing} newest-first by
        // semver comparator before truncating, so the cap selects the top 50
        // and 9.9.9 lands at index 0.
        final java.util.List<String> versions = new java.util.ArrayList<>();
        for (int i = 0; i < 99; i++) {
            versions.add("1.0." + i);
        }
        versions.add("9.9.9");
        final java.time.Instant fresh = java.time.Instant.now()
            .minus(java.time.Duration.ofDays(2));
        final java.util.Map<String, java.time.Instant> registryDates = new java.util.HashMap<>();
        registryDates.put("9.9.9", fresh);
        PublishDateRegistries.installDefault(new FakePublishDateRegistry(registryDates));
        final DateAwareCooldownService dateAware = new DateAwareCooldownService(
            this.settings.minimumAllowedAge()
        );
        final MetadataFilterService capService = new MetadataFilterService(
            dateAware,
            this.settings,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );
        final TestMetadataParser parser = new TestMetadataParser(versions, "9.9.9");
        final TestMetadataFilter filter = new TestMetadataFilter();
        final TestMetadataRewriter rewriter = new TestMetadataRewriter();
        capService.filterMetadata(
            "maven", "test-repo", "com.example.lib",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter
        ).get();
        assertThat(
            "9.9.9 sits at index 99 (beyond cap=50); newest-first sort must "
                + "still pick it up for the registry backfill and the resulting "
                + "fresh date must trigger a cooldown block",
            filter.lastBlockedVersions.contains("9.9.9"), equalTo(true)
        );
        assertThat(
            "Only the one version with a registry-resolved date should block",
            filter.lastBlockedVersions.size(), equalTo(1)
        );
    }

    // Test implementations

    /**
     * Test cooldown service with configurable blockedUntil for testing cache expiry.
     */
    private static final class ShortExpiryTestCooldownService implements CooldownService {
        private final Set<String> blockedVersions = new HashSet<>();
        private final Instant blockedUntil;

        ShortExpiryTestCooldownService(final Instant blockedUntil) {
            this.blockedUntil = blockedUntil;
        }

        void blockVersion(final String pkg, final String version) {
            this.blockedVersions.add(pkg + "@" + version);
        }

        void expireBlock(final String pkg, final String version) {
            this.blockedVersions.remove(pkg + "@" + version);
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request,
            final CooldownInspector inspector
        ) {
            final String key = request.artifact() + "@" + request.version();
            if (this.blockedVersions.contains(key)) {
                return CompletableFuture.completedFuture(
                    CooldownResult.blocked(new com.auto1.pantera.cooldown.api.CooldownBlock(
                        request.repoType(),
                        request.repoName(),
                        request.artifact(),
                        request.version(),
                        com.auto1.pantera.cooldown.api.CooldownReason.FRESH_RELEASE,
                        Instant.now(),
                        this.blockedUntil, // Use configurable blockedUntil
                        java.util.Collections.emptyList()
                    ))
                );
            }
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request,
            final Optional<Instant> knownReleaseDate
        ) {
            return this.evaluate(request, null);
        }

        @Override
        public CompletableFuture<Void> unblock(
            String repoType, String repoName, String artifact, String version, String actor
        ) {
            this.blockedVersions.remove(artifact + "@" + version);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(String repoType, String repoName, String actor) {
            this.blockedVersions.clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<java.util.List<com.auto1.pantera.cooldown.api.CooldownBlock>> activeBlocks(
            String repoType, String repoName
        ) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }

    private static final class TestCooldownService implements CooldownService {
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
                    CooldownResult.blocked(new com.auto1.pantera.cooldown.api.CooldownBlock(
                        request.repoType(),
                        request.repoName(),
                        request.artifact(),
                        request.version(),
                        com.auto1.pantera.cooldown.api.CooldownReason.FRESH_RELEASE,
                        Instant.now(),
                        Instant.now().plus(Duration.ofDays(7)),
                        java.util.Collections.emptyList()
                    ))
                );
            }
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request,
            final Optional<Instant> knownReleaseDate
        ) {
            return this.evaluate(request, null);
        }

        @Override
        public CompletableFuture<Void> unblock(
            String repoType, String repoName, String artifact, String version, String actor
        ) {
            this.blockedVersions.remove(artifact + "@" + version);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(String repoType, String repoName, String actor) {
            this.blockedVersions.clear();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<java.util.List<com.auto1.pantera.cooldown.api.CooldownBlock>> activeBlocks(
            String repoType, String repoName
        ) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }

    private static final class TestMetadataParser implements MetadataParser<List<String>> {
        private final List<String> versions;
        private final String latest;
        int parseCount = 0;

        TestMetadataParser(final List<String> versions, final String latest) {
            this.versions = versions;
            this.latest = latest;
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
        public String contentType() {
            return "application/json";
        }
    }

    private static final class TestMetadataFilter implements MetadataFilter<List<String>> {
        Set<String> lastBlockedVersions = new HashSet<>();
        String lastNewLatest = null;

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

    /**
     * Simple test inspector that returns release dates from a configurable map.
     */
    private static final class TestCooldownInspector implements CooldownInspector {
        private final Map<String, Instant> releaseDates;

        TestCooldownInspector() {
            // Default: all versions released long ago (allowed)
            this.releaseDates = new java.util.HashMap<>();
        }

        TestCooldownInspector(final Map<String, Instant> releaseDates) {
            this.releaseDates = new java.util.HashMap<>(releaseDates);
        }

        void setReleaseDate(final String version, final Instant date) {
            this.releaseDates.put(version, date);
        }

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
            return CompletableFuture.completedFuture(
                Optional.ofNullable(this.releaseDates.get(version))
            );
        }

        @Override
        public CompletableFuture<List<com.auto1.pantera.cooldown.api.CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }

    /**
     * Cooldown service that blocks a version iff the {@code knownReleaseDate}
     * supplied by the caller is newer than {@code now - cooldownDuration}.
     * Mirrors what JdbcCooldownService.shouldBlockNewArtifact actually does:
     * the block decision is a function of the date, not a static blocked-set.
     */
    private static final class DateAwareCooldownService implements CooldownService {

        private final Duration cooldownDuration;

        DateAwareCooldownService(final Duration cooldownDuration) {
            this.cooldownDuration = cooldownDuration;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<CooldownResult> evaluateWithKnownDate(
            final CooldownRequest request, final Optional<Instant> knownReleaseDate
        ) {
            if (knownReleaseDate.isEmpty()) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            final Instant cutoff = Instant.now().minus(this.cooldownDuration);
            if (knownReleaseDate.get().isAfter(cutoff)) {
                return CompletableFuture.completedFuture(
                    CooldownResult.blocked(new com.auto1.pantera.cooldown.api.CooldownBlock(
                        request.repoType(),
                        request.repoName(),
                        request.artifact(),
                        request.version(),
                        com.auto1.pantera.cooldown.api.CooldownReason.FRESH_RELEASE,
                        Instant.now(),
                        knownReleaseDate.get().plus(this.cooldownDuration),
                        java.util.Collections.emptyList()
                    ))
                );
            }
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName, final String artifact,
            final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<java.util.List<com.auto1.pantera.cooldown.api.CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(java.util.Collections.emptyList());
        }
    }

    /**
     * Fake PublishDateRegistry that returns canned dates from a map. Stands in
     * for {@code DbPublishDateRegistry} during unit tests so the filter
     * exercises the backfill path without a real database.
     */
    private static final class FakePublishDateRegistry implements PublishDateRegistry {

        private final Map<String, Instant> dates;

        FakePublishDateRegistry(final Map<String, Instant> dates) {
            this.dates = new java.util.HashMap<>(dates);
        }

        @Override
        public CompletableFuture<Optional<Instant>> publishDate(
            final String repoType, final String name, final String version
        ) {
            return CompletableFuture.completedFuture(
                Optional.ofNullable(this.dates.get(version))
            );
        }
    }

    /**
     * Captures records emitted on the {@code artifact.audit} logger so tests
     * can assert exactly which audit events a code path produced. Install per
     * test with {@link #install()} and detach with {@link #remove()} in a
     * finally block.
     */
    private static final class AuditCapture
        extends org.apache.logging.log4j.core.appender.AbstractAppender {

        private static final String NAME = "MetadataFilterAuditCapture";
        private static final String AUDIT_LOGGER = "artifact.audit";

        private final List<org.apache.logging.log4j.core.LogEvent> events =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private AuditCapture() {
            super(NAME, null, null, true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        static AuditCapture install() {
            final AuditCapture capture = new AuditCapture();
            capture.start();
            final org.apache.logging.log4j.core.LoggerContext lc =
                (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            final org.apache.logging.log4j.core.config.Configuration cfg = lc.getConfiguration();
            cfg.getLoggerConfig(AUDIT_LOGGER).addAppender(capture, null, null);
            cfg.getRootLogger().addAppender(capture, null, null);
            lc.updateLoggers();
            return capture;
        }

        void remove() {
            final org.apache.logging.log4j.core.LoggerContext lc =
                (org.apache.logging.log4j.core.LoggerContext)
                    org.apache.logging.log4j.LogManager.getContext(false);
            final org.apache.logging.log4j.core.config.Configuration cfg = lc.getConfiguration();
            cfg.getLoggerConfig(AUDIT_LOGGER).removeAppender(NAME);
            cfg.getRootLogger().removeAppender(NAME);
            this.stop();
            lc.updateLoggers();
        }

        List<org.apache.logging.log4j.core.LogEvent> resolutionEvents() {
            synchronized (this.events) {
                return this.events.stream()
                    .filter(event -> "artifact_resolution".equals(
                        String.valueOf(field(event, "event.action"))
                    ))
                    .collect(java.util.stream.Collectors.toList());
            }
        }

        static Object field(
            final org.apache.logging.log4j.core.LogEvent event, final String key
        ) {
            final org.apache.logging.log4j.message.Message msg = event.getMessage();
            if (msg instanceof org.apache.logging.log4j.message.MapMessage<?, ?> map) {
                return map.getData().get(key);
            }
            return null;
        }

        @Override
        public void append(final org.apache.logging.log4j.core.LogEvent event) {
            this.events.add(event.toImmutable());
        }
    }
}
