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
package com.auto1.pantera.maven.cooldown;

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metadata.MetadataFilterService;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.PublishDateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * End-to-end smoke test for the Maven cooldown filter. Exercises the real
 * {@link MavenMetadataParser} / {@link MavenMetadataFilter} /
 * {@link MavenMetadataRewriter} triple through {@link MetadataFilterService},
 * with a fake {@link PublishDateRegistry} supplying the per-version dates that
 * artifact-level {@code maven-metadata.xml} structurally lacks. This is the
 * regression scaffold for the bug where the filter logged
 * {@code "154 total versions, 0 blocked"} for Guava 33.6.0-jre despite the
 * version being well inside the gradle-proxy cooldown window — the cause was
 * {@link MetadataFilterService} only consulting inline dates and never the
 * registry. The post-fix expectation is that any fresh version with a
 * registry-supplied date is removed from {@code <versions>} and that
 * {@code <release>} is rewritten away from the blocked version.
 *
 * @since 2.2.0
 */
final class MavenCooldownEndToEndTest {

    /**
     * Sample maven-metadata.xml containing a fresh version (33.6.0-jre) that
     * the registry knows about and several older versions the registry has
     * no record of. The fresh version is also the current {@code <latest>}
     * / {@code <release>}, so the filter must rewrite both.
     */
    private static final String GUAVA_METADATA = """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <groupId>com.google.guava</groupId>
          <artifactId>guava</artifactId>
          <versioning>
            <latest>33.6.0-jre</latest>
            <release>33.6.0-jre</release>
            <versions>
              <version>33.2.0-jre</version>
              <version>33.3.0-jre</version>
              <version>33.4.0-jre</version>
              <version>33.5.0-jre</version>
              <version>33.6.0-jre</version>
            </versions>
            <lastUpdated>20260414000000</lastUpdated>
          </versioning>
        </metadata>
        """;

    private PublishDateRegistry previousRegistry;

    @BeforeEach
    void setUp() {
        this.previousRegistry = PublishDateRegistries.instance();
    }

    @AfterEach
    void tearDown() {
        if (this.previousRegistry != null) {
            PublishDateRegistries.installDefault(this.previousRegistry);
        }
    }

    @Test
    void blocksFreshGuavaViaRegistryAndRewritesReleaseTag() throws Exception {
        // Date 2 days ago — well inside the 60-day gradle-proxy cooldown.
        final Instant fresh = Instant.now().minus(Duration.ofDays(2));
        final Map<String, Instant> registryDates = new HashMap<>();
        registryDates.put("33.6.0-jre", fresh);
        PublishDateRegistries.installDefault(new FakeRegistry(registryDates));
        final CooldownSettings settings = new CooldownSettings(true, Duration.ofDays(60));
        final MetadataFilterService service = new MetadataFilterService(
            new DateAwareCooldownService(settings.minimumAllowedAge()),
            settings,
            new CooldownCache(),
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            50
        );
        final byte[] result = service.filterMetadata(
            "maven", "test-repo", "com.google.guava.guava",
            GUAVA_METADATA.getBytes(StandardCharsets.UTF_8),
            new MavenMetadataParser(),
            new MavenMetadataFilter(),
            new MavenMetadataRewriter()
        ).get();
        final String xml = new String(result, StandardCharsets.UTF_8);
        assertThat(
            "Blocked version must be stripped from <versions>",
            xml, not(containsString("<version>33.6.0-jre</version>"))
        );
        assertThat(
            "Surviving 33.5.0-jre must remain in <versions>",
            xml, containsString("<version>33.5.0-jre</version>")
        );
        assertThat(
            "<release> must no longer reference the blocked version",
            xml, not(containsString("<release>33.6.0-jre</release>"))
        );
        // <release> must be rewritten to one of the surviving stable versions;
        // MetadataFilterService picks the new latest from allVersions in the
        // order returned by extractVersions (document order for Maven), so
        // exactly which surviving version is picked is a property of that
        // upstream selection logic and not what this test guards. The key
        // post-fix invariant is that <release> is not still 33.6.0-jre.
        assertThat(
            "<release> must point at a surviving non-blocked version",
            xml.contains("<release>33.2.0-jre</release>")
                || xml.contains("<release>33.3.0-jre</release>")
                || xml.contains("<release>33.4.0-jre</release>")
                || xml.contains("<release>33.5.0-jre</release>"),
            equalTo(true)
        );
    }

    /**
     * Cooldown service that blocks iff the known release date falls inside
     * the cooldown window. Mirrors the production
     * {@code JdbcCooldownService.shouldBlockNewArtifact} contract without a
     * database.
     */
    private static final class DateAwareCooldownService implements CooldownService {

        private final Duration cooldown;

        DateAwareCooldownService(final Duration cooldown) {
            this.cooldown = cooldown;
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
            final Instant cutoff = Instant.now().minus(this.cooldown);
            if (knownReleaseDate.get().isAfter(cutoff)) {
                return CompletableFuture.completedFuture(
                    CooldownResult.blocked(new CooldownBlock(
                        request.repoType(),
                        request.repoName(),
                        request.artifact(),
                        request.version(),
                        CooldownReason.FRESH_RELEASE,
                        Instant.now(),
                        knownReleaseDate.get().plus(this.cooldown),
                        Collections.emptyList()
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
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    /**
     * In-memory {@link PublishDateRegistry} that returns canned dates.
     */
    private static final class FakeRegistry implements PublishDateRegistry {

        private final Map<String, Instant> dates;

        FakeRegistry(final Map<String, Instant> dates) {
            this.dates = new HashMap<>(dates);
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
}
