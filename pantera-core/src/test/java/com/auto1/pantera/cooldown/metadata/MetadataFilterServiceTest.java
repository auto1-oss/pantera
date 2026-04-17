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
            rewriter,
            Optional.of(inspector)
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
            rewriter,
            Optional.of(inspector)
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
                rewriter,
                Optional.of(inspector)
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
            new TestMetadataRewriter(),
            Optional.empty()
        ).get();

        // Should return raw metadata unchanged
        assertThat(result, equalTo(rawMetadata));
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
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        final int firstParseCount = parser.parseCount;

        // Second call - should hit cache
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
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
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        final int firstParseCount = parser.parseCount;

        // Invalidate
        this.service.invalidate("npm", "test-repo", "test-pkg");

        // Third call - should reprocess after invalidation
        this.service.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
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
            parser, filter, rewriter, Optional.of(inspector)
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
            parser, filter, rewriter, Optional.of(inspector)
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
            parser1, filter, rewriter, Optional.of(inspector)
        ).get();

        this.service.filterMetadata(
            "npm", "test-repo", "pkg2",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser2, filter, rewriter, Optional.of(inspector)
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
            parser1, filter, rewriter, Optional.of(inspector)
        ).get();

        this.service.filterMetadata(
            "npm", "test-repo", "pkg2",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser2, filter, rewriter, Optional.of(inspector)
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
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        assertThat("3.0.0 should be blocked initially", 
            filter.lastBlockedVersions.contains("3.0.0"), equalTo(true));

        final int firstParseCount = parser.parseCount;

        // Wait for block to expire
        Thread.sleep(150);

        // Simulate block expiry in cooldown service
        shortExpiryService.expireBlock("test-pkg", "3.0.0");

        // Second request after expiry - cache should have expired, 3.0.0 should be allowed
        shortExpiryMetadataService.filterMetadata(
            "npm", "test-repo", "test-pkg",
            "raw".getBytes(StandardCharsets.UTF_8),
            parser, filter, rewriter, Optional.of(inspector)
        ).get();

        // Should have re-parsed (cache expired based on blockedUntil)
        assertThat("Should re-parse after cache expiry", 
            parser.parseCount, equalTo(firstParseCount + 1));
        // 3.0.0 should no longer be blocked
        assertThat("3.0.0 should not be blocked after expiry", 
            filter.lastBlockedVersions.contains("3.0.0"), equalTo(false));
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
}
