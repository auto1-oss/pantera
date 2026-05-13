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
package com.auto1.pantera.publishdate;

import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
final class DbPublishDateRegistryTest {

    @Container
    static final PostgreSQLContainer<?> PG = PostgreSQLTestConfig.createContainer();

    private HikariDataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(2);
        this.ds = new HikariDataSource(cfg);
        try (Connection conn = this.ds.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS artifact_publish_dates");
            stmt.execute(
                "CREATE TABLE artifact_publish_dates ("
                + "  repo_type VARCHAR(32) NOT NULL,"
                + "  name VARCHAR(512) NOT NULL,"
                + "  version VARCHAR(128) NOT NULL,"
                + "  published_at TIMESTAMPTZ NOT NULL,"
                + "  source VARCHAR(64) NOT NULL,"
                + "  fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),"
                + "  PRIMARY KEY (repo_type, name, version)"
                + ")"
            );
        }
    }

    @AfterEach
    void tearDown() {
        if (this.ds != null) {
            this.ds.close();
        }
    }

    @Test
    void firstCallHitsSourceAndPersistsToDb() throws Exception {
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource fakeMaven = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(Instant.parse("2020-01-01T00:00:00Z"))
                );
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of("maven", fakeMaven));

        final Optional<Instant> result = reg.publishDate(
            "maven", "org.apache.commons.commons-lang3", "3.12.0"
        ).get();
        assertEquals(Optional.of(Instant.parse("2020-01-01T00:00:00Z")), result);
        assertEquals(1, sourceCalls.get());

        try (Connection conn = this.ds.getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                 "SELECT published_at, source FROM artifact_publish_dates "
                 + "WHERE name='org.apache.commons.commons-lang3'")) {
            assertTrue(rs.next());
            assertEquals(Instant.parse("2020-01-01T00:00:00Z"), rs.getTimestamp(1).toInstant());
            assertEquals("fake", rs.getString(2));
        }
    }

    @Test
    void secondCallHitsL1Cache() throws Exception {
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource fakeMaven = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(Instant.parse("2020-01-01T00:00:00Z"))
                );
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of("maven", fakeMaven));

        reg.publishDate("maven", "x.y", "1.0").get();
        reg.publishDate("maven", "x.y", "1.0").get();
        reg.publishDate("maven", "x.y", "1.0").get();
        assertEquals(1, sourceCalls.get(), "L1 should serve repeat lookups");
    }

    @Test
    void afterRestartL2DbServesPriorWrites() throws Exception {
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource fakeMaven = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(Instant.parse("2020-01-01T00:00:00Z"))
                );
            });
        new DbPublishDateRegistry(this.ds, Map.of("maven", fakeMaven))
            .publishDate("maven", "x.y", "1.0").get();
        assertEquals(1, sourceCalls.get());

        // simulate restart: brand-new registry instance, empty L1
        final DbPublishDateRegistry fresh = new DbPublishDateRegistry(this.ds, Map.of("maven", fakeMaven));
        final Optional<Instant> result = fresh.publishDate("maven", "x.y", "1.0").get();
        assertEquals(Optional.of(Instant.parse("2020-01-01T00:00:00Z")), result);
        assertEquals(1, sourceCalls.get(), "DB row should be served — no second source call");
    }

    @Test
    void unknownRepoTypeReturnsEmpty() throws Exception {
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of());
        assertEquals(Optional.empty(), reg.publishDate("unknown", "x", "1.0").get());
    }

    @Test
    void sourceFailureIsBrieflyCachedToPreventRetryStorms() throws Exception {
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource flaky = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of("maven", flaky));

        assertEquals(Optional.empty(), reg.publishDate("maven", "x.y", "1.0").get());
        // Within the 60s negative TTL, repeated lookups short-circuit on the
        // negative L1 cache instead of hammering the failing upstream — this
        // is the fix for "many timeout errors" under degraded upstream.
        assertEquals(Optional.empty(), reg.publishDate("maven", "x.y", "1.0").get());
        assertEquals(1, sourceCalls.get(),
            "second lookup must hit negativeL1, not call source again");
    }

    @Test
    void cacheOnlyModeNeverInvokesSource() throws Exception {
        // Track 5 Phase 3C contract: when the caller passes CACHE_ONLY,
        // the registry MUST return Optional.empty() on L1+L2 miss without
        // firing the PublishDateSource. This is what makes the SPI safe
        // for cache-hit hot paths — even if someone later re-introduces a
        // cooldown gate on a cache hit, the inspector chain physically
        // cannot reach upstream.
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource fakeMaven = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(Instant.parse("2020-01-01T00:00:00Z"))
                );
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(
            this.ds, Map.of("maven", fakeMaven)
        );
        final Optional<Instant> result = reg.publishDate(
            "maven", "x.y", "1.0", PublishDateRegistry.Mode.CACHE_ONLY
        ).get();
        assertEquals(Optional.empty(), result,
            "CACHE_ONLY on L1+L2 miss returns empty");
        assertEquals(0, sourceCalls.get(),
            "CACHE_ONLY must NOT fire the PublishDateSource");
    }

    @Test
    void cacheOnlyAfterNetworkFallbackHitsL1OrL2() throws Exception {
        // Cache-miss path uses NETWORK_FALLBACK, populates L1+L2; the
        // subsequent CACHE_ONLY read on the cache-hit path then finds the
        // value locally without any source call. This is the steady-state
        // pattern Phase 3C documents — first asker pays one upstream HEAD
        // via NETWORK_FALLBACK, every subsequent asker is pure-local.
        final AtomicInteger sourceCalls = new AtomicInteger();
        final Instant published = Instant.parse("2024-09-15T12:34:56Z");
        final PublishDateSource fakeMaven = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.of(published));
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(
            this.ds, Map.of("maven", fakeMaven)
        );
        // First call: NETWORK_FALLBACK → source fires.
        reg.publishDate(
            "maven", "x.y", "1.0", PublishDateRegistry.Mode.NETWORK_FALLBACK
        ).get();
        assertEquals(1, sourceCalls.get());
        // Subsequent CACHE_ONLY: L1 hit, no source call.
        final Optional<Instant> cacheOnly = reg.publishDate(
            "maven", "x.y", "1.0", PublishDateRegistry.Mode.CACHE_ONLY
        ).get();
        assertEquals(Optional.of(published), cacheOnly);
        assertEquals(1, sourceCalls.get(),
            "CACHE_ONLY served from L1 — source count unchanged");
        // Fresh registry instance simulates a restart: L1 empty, L2 has it,
        // CACHE_ONLY still pure-local (no source call).
        final AtomicInteger postRestartCalls = new AtomicInteger();
        final PublishDateSource shouldNotFire = stubSource("maven", "fake2",
            (n, v) -> {
                postRestartCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            });
        final DbPublishDateRegistry fresh = new DbPublishDateRegistry(
            this.ds, Map.of("maven", shouldNotFire)
        );
        final Optional<Instant> afterRestart = fresh.publishDate(
            "maven", "x.y", "1.0", PublishDateRegistry.Mode.CACHE_ONLY
        ).get();
        assertEquals(Optional.of(published), afterRestart,
            "CACHE_ONLY reads from L2 (Postgres) when L1 is cold");
        assertEquals(0, postRestartCalls.get(),
            "L2 hit must not fall through to source even on cold L1");
    }

    private static PublishDateSource stubSource(
        final String repoType, final String id,
        final BiFunction<String, String, CompletableFuture<Optional<Instant>>> fn
    ) {
        return new PublishDateSource() {
            @Override public String repoType() { return repoType; }
            @Override public String sourceId() { return id; }
            @Override public CompletableFuture<Optional<Instant>> fetch(final String n, final String v) {
                return fn.apply(n, v);
            }
        };
    }
}
