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
package com.auto1.pantera.settings.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cache.ValkeyConnection;
import com.auto1.pantera.http.filter.Filters;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link PublishingFiltersCache}.
 *
 * @since 1.20.13
 */
@Testcontainers
final class PublishingFiltersCacheTest {

    /**
     * Valkey container.
     */
    @Container
    @SuppressWarnings("rawtypes")
    private static final GenericContainer VALKEY =
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    /**
     * Instance A connection (publisher side).
     */
    private ValkeyConnection connA;

    /**
     * Instance B connection (subscriber side).
     */
    private ValkeyConnection connB;

    /**
     * Pub/sub for instance A.
     */
    private CacheInvalidationPubSub pubsubA;

    /**
     * Pub/sub for instance B.
     */
    private CacheInvalidationPubSub pubsubB;

    @BeforeEach
    void setUp() {
        final String host = VALKEY.getHost();
        final int port = VALKEY.getMappedPort(6379);
        this.connA = new ValkeyConnection(host, port, Duration.ofSeconds(5));
        this.connB = new ValkeyConnection(host, port, Duration.ofSeconds(5));
        this.pubsubA = new CacheInvalidationPubSub(this.connA);
        this.pubsubB = new CacheInvalidationPubSub(this.connB);
    }

    @AfterEach
    void tearDown() {
        if (this.pubsubA != null) {
            this.pubsubA.close();
        }
        if (this.pubsubB != null) {
            this.pubsubB.close();
        }
        if (this.connA != null) {
            this.connA.close();
        }
        if (this.connB != null) {
            this.connB.close();
        }
    }

    @Test
    void delegatesFiltersToInnerCache() {
        final RecordingFiltersCache inner = new RecordingFiltersCache();
        final PublishingFiltersCache cache =
            new PublishingFiltersCache(inner, this.pubsubA);
        cache.filters("my-repo", null);
        MatcherAssert.assertThat(
            "Should delegate filters() to inner cache",
            inner.queriedRepos(),
            Matchers.contains("my-repo")
        );
    }

    @Test
    void delegatesSizeToInnerCache() {
        final RecordingFiltersCache inner = new RecordingFiltersCache();
        final PublishingFiltersCache cache =
            new PublishingFiltersCache(inner, this.pubsubA);
        MatcherAssert.assertThat(
            "Should delegate size() to inner cache",
            cache.size(),
            Matchers.is(42L)
        );
    }

    @Test
    void invalidateDelegatesAndPublishes() {
        final RecordingFiltersCache innerA = new RecordingFiltersCache();
        final RecordingFiltersCache innerB = new RecordingFiltersCache();
        this.pubsubB.register("filters", innerB);
        final PublishingFiltersCache cache =
            new PublishingFiltersCache(innerA, this.pubsubA);
        cache.invalidate("docker-repo");
        MatcherAssert.assertThat(
            "Should invalidate inner cache directly",
            innerA.invalidatedKeys(),
            Matchers.contains("docker-repo")
        );
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "Remote instance should receive invalidation",
                    innerB.invalidatedKeys(),
                    Matchers.contains("docker-repo")
                )
            );
    }

    @Test
    void invalidateAllDelegatesAndPublishes() {
        final RecordingFiltersCache innerA = new RecordingFiltersCache();
        final RecordingFiltersCache innerB = new RecordingFiltersCache();
        this.pubsubB.register("filters", innerB);
        final PublishingFiltersCache cache =
            new PublishingFiltersCache(innerA, this.pubsubA);
        cache.invalidateAll();
        MatcherAssert.assertThat(
            "Should invalidateAll on inner cache directly",
            innerA.allInvalidations(),
            Matchers.is(1)
        );
        Awaitility.await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(
                () -> MatcherAssert.assertThat(
                    "Remote instance should receive invalidateAll",
                    innerB.allInvalidations(),
                    Matchers.is(1)
                )
            );
    }

    /**
     * Recording implementation of {@link FiltersCache} for test verification.
     */
    private static final class RecordingFiltersCache implements FiltersCache {
        /**
         * Repo names queried via filters().
         */
        private final List<String> repos;

        /**
         * Keys invalidated.
         */
        private final List<String> keys;

        /**
         * Count of invalidateAll calls.
         */
        private int allCount;

        RecordingFiltersCache() {
            this.repos = Collections.synchronizedList(new ArrayList<>(4));
            this.keys = Collections.synchronizedList(new ArrayList<>(4));
        }

        @Override
        public Optional<Filters> filters(final String reponame,
            final YamlMapping repoyaml) {
            this.repos.add(reponame);
            return Optional.empty();
        }

        @Override
        public long size() {
            return 42L;
        }

        @Override
        public void invalidate(final String reponame) {
            this.keys.add(reponame);
        }

        @Override
        public void invalidateAll() {
            this.allCount += 1;
        }

        List<String> queriedRepos() {
            return this.repos;
        }

        List<String> invalidatedKeys() {
            return this.keys;
        }

        int allInvalidations() {
            return this.allCount;
        }
    }
}
