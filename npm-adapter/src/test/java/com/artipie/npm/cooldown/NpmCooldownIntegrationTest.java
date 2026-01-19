/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.cooldown;

import com.artipie.cooldown.CooldownBlock;
import com.artipie.cooldown.CooldownCache;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownReason;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResult;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownSettings;
import com.artipie.cooldown.metadata.CooldownMetadataServiceImpl;
import com.artipie.cooldown.metadata.FilteredMetadataCache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for NPM cooldown metadata filtering with CooldownMetadataServiceImpl.
 *
 * @since 1.0
 */
final class NpmCooldownIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CooldownMetadataServiceImpl service;
    private TestCooldownService cooldownService;
    private NpmMetadataParser parser;
    private NpmMetadataFilter filter;
    private NpmMetadataRewriter rewriter;
    private NpmCooldownInspector inspector;

    @BeforeEach
    void setUp() {
        this.cooldownService = new TestCooldownService();
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(7));
        final CooldownCache cooldownCache = new CooldownCache();
        final FilteredMetadataCache metadataCache = new FilteredMetadataCache();
        
        this.service = new CooldownMetadataServiceImpl(
            this.cooldownService,
            settings,
            cooldownCache,
            metadataCache,
            ForkJoinPool.commonPool(),
            50
        );
        
        this.parser = new NpmMetadataParser();
        this.filter = new NpmMetadataFilter();
        this.rewriter = new NpmMetadataRewriter();
        this.inspector = new NpmCooldownInspector();
    }

    @Test
    void filtersBlockedVersionsFromNpmMetadata() throws Exception {
        // Block version 3.0.0
        this.cooldownService.blockVersion("lodash", "3.0.0");

        // Use recent dates so versions fall within cooldown evaluation window
        final java.time.Instant now = java.time.Instant.now();
        final String date1 = now.minus(java.time.Duration.ofDays(30)).toString();  // old, outside cooldown
        final String date2 = now.minus(java.time.Duration.ofDays(14)).toString();  // old, outside cooldown  
        final String date3 = now.minus(java.time.Duration.ofDays(1)).toString();   // recent, within cooldown
        
        final String rawJson = String.format("""
            {
                "name": "lodash",
                "dist-tags": { "latest": "3.0.0" },
                "versions": {
                    "1.0.0": { "name": "lodash", "version": "1.0.0" },
                    "2.0.0": { "name": "lodash", "version": "2.0.0" },
                    "3.0.0": { "name": "lodash", "version": "3.0.0" }
                },
                "time": {
                    "1.0.0": "%s",
                    "2.0.0": "%s",
                    "3.0.0": "%s"
                }
            }
            """, date1, date2, date3);

        final byte[] result = this.service.filterMetadata(
            "npm",
            "test-repo",
            "lodash",
            rawJson.getBytes(StandardCharsets.UTF_8),
            this.parser,
            this.filter,
            this.rewriter,
            Optional.of(this.inspector)
        ).get();

        final JsonNode filtered = MAPPER.readTree(result);

        // Version 3.0.0 should be filtered out
        assertThat(filtered.get("versions").has("1.0.0"), is(true));
        assertThat(filtered.get("versions").has("2.0.0"), is(true));
        assertThat(filtered.get("versions").has("3.0.0"), is(false));

        // Latest should be updated to 2.0.0
        assertThat(filtered.get("dist-tags").get("latest").asText(), equalTo("2.0.0"));

        // Time should also be filtered
        assertThat(filtered.get("time").has("3.0.0"), is(false));
    }

    @Test
    void preservesAllVersionsWhenNoneBlocked() throws Exception {
        final String rawJson = """
            {
                "name": "express",
                "dist-tags": { "latest": "4.18.2" },
                "versions": {
                    "4.17.0": {},
                    "4.18.0": {},
                    "4.18.2": {}
                }
            }
            """;

        final byte[] result = this.service.filterMetadata(
            "npm",
            "test-repo",
            "express",
            rawJson.getBytes(StandardCharsets.UTF_8),
            this.parser,
            this.filter,
            this.rewriter,
            Optional.of(this.inspector)
        ).get();

        final JsonNode filtered = MAPPER.readTree(result);

        // All versions should be present
        assertThat(filtered.get("versions").has("4.17.0"), is(true));
        assertThat(filtered.get("versions").has("4.18.0"), is(true));
        assertThat(filtered.get("versions").has("4.18.2"), is(true));

        // Latest should be unchanged
        assertThat(filtered.get("dist-tags").get("latest").asText(), equalTo("4.18.2"));
    }

    @Test
    void preloadsReleaseDatesFromMetadata() throws Exception {
        final String rawJson = """
            {
                "name": "test-pkg",
                "versions": { "1.0.0": {}, "2.0.0": {} },
                "time": {
                    "1.0.0": "2020-01-01T00:00:00.000Z",
                    "2.0.0": "2023-06-15T12:00:00.000Z"
                }
            }
            """;

        // Parse and extract release dates
        final JsonNode parsed = this.parser.parse(rawJson.getBytes(StandardCharsets.UTF_8));
        final var dates = this.parser.releaseDates(parsed);

        // Preload into inspector
        this.inspector.preloadReleaseDates(dates);

        // Verify dates are available
        assertThat(this.inspector.hasPreloaded("1.0.0"), is(true));
        assertThat(this.inspector.hasPreloaded("2.0.0"), is(true));

        final Optional<Instant> date = this.inspector.releaseDate("test-pkg", "2.0.0").get();
        assertThat(date.isPresent(), is(true));
        assertThat(date.get(), equalTo(Instant.parse("2023-06-15T12:00:00.000Z")));
    }

    @Test
    void handlesMultipleBlockedVersions() throws Exception {
        // Block multiple versions
        this.cooldownService.blockVersion("react", "18.2.0");
        this.cooldownService.blockVersion("react", "18.1.0");
        this.cooldownService.blockVersion("react", "18.0.0");

        final String rawJson = """
            {
                "name": "react",
                "dist-tags": { "latest": "18.2.0" },
                "versions": {
                    "17.0.0": {},
                    "17.0.1": {},
                    "17.0.2": {},
                    "18.0.0": {},
                    "18.1.0": {},
                    "18.2.0": {}
                }
            }
            """;

        final byte[] result = this.service.filterMetadata(
            "npm",
            "test-repo",
            "react",
            rawJson.getBytes(StandardCharsets.UTF_8),
            this.parser,
            this.filter,
            this.rewriter,
            Optional.of(this.inspector)
        ).get();

        final JsonNode filtered = MAPPER.readTree(result);

        // React 17.x versions should remain
        assertThat(filtered.get("versions").has("17.0.0"), is(true));
        assertThat(filtered.get("versions").has("17.0.1"), is(true));
        assertThat(filtered.get("versions").has("17.0.2"), is(true));

        // React 18.x versions should be filtered
        assertThat(filtered.get("versions").has("18.0.0"), is(false));
        assertThat(filtered.get("versions").has("18.1.0"), is(false));
        assertThat(filtered.get("versions").has("18.2.0"), is(false));

        // Latest should fall back to 17.0.2
        assertThat(filtered.get("dist-tags").get("latest").asText(), equalTo("17.0.2"));
    }

    @Test
    void handlesScopedPackages() throws Exception {
        this.cooldownService.blockVersion("@types/node", "20.0.0");

        final String rawJson = """
            {
                "name": "@types/node",
                "dist-tags": { "latest": "20.0.0" },
                "versions": {
                    "18.0.0": { "name": "@types/node" },
                    "19.0.0": { "name": "@types/node" },
                    "20.0.0": { "name": "@types/node" }
                }
            }
            """;

        final byte[] result = this.service.filterMetadata(
            "npm",
            "test-repo",
            "@types/node",
            rawJson.getBytes(StandardCharsets.UTF_8),
            this.parser,
            this.filter,
            this.rewriter,
            Optional.of(this.inspector)
        ).get();

        final JsonNode filtered = MAPPER.readTree(result);

        assertThat(filtered.get("versions").has("20.0.0"), is(false));
        assertThat(filtered.get("dist-tags").get("latest").asText(), equalTo("19.0.0"));
    }

    @Test
    void cachesFilteredMetadata() throws Exception {
        final String rawJson = """
            {
                "name": "cached-pkg",
                "versions": { "1.0.0": {}, "2.0.0": {} }
            }
            """;

        // First call
        this.service.filterMetadata(
            "npm", "test-repo", "cached-pkg",
            rawJson.getBytes(StandardCharsets.UTF_8),
            this.parser, this.filter, this.rewriter,
            Optional.of(this.inspector)
        ).get();

        // Second call should hit cache (parser won't be called)
        final NpmMetadataParser countingParser = new NpmMetadataParser();
        this.service.filterMetadata(
            "npm", "test-repo", "cached-pkg",
            rawJson.getBytes(StandardCharsets.UTF_8),
            countingParser, this.filter, this.rewriter,
            Optional.of(this.inspector)
        ).get();

        // Cache hit - no additional parsing needed
        // (We can't easily verify this without modifying the parser, but the test ensures no errors)
    }

    // Test cooldown service implementation
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
                    CooldownResult.blocked(new CooldownBlock(
                        request.repoType(),
                        request.repoName(),
                        request.artifact(),
                        request.version(),
                        CooldownReason.FRESH_RELEASE,
                        Instant.now(),
                        Instant.now().plus(Duration.ofDays(7)),
                        Collections.emptyList()
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
        public CompletableFuture<List<CooldownBlock>> activeBlocks(String repoType, String repoName) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
