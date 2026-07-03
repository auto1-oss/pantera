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
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Verifies the historical-row alias fallback added to
 * {@link DbPublishDateRegistry} in 2.2.0. Pre-2.2.0 the Maven proxy package
 * processor hardcoded {@code repo_type='maven-proxy'} for every event, so
 * {@code gradle-proxy} rows landed under the wrong key. The cooldown
 * evaluator queries with the correct {@code gradle-proxy} repo_type and
 * therefore missed those rows, falling through to an upstream HEAD that the
 * forward fix is supposed to avoid.
 *
 * <p>The alias path: on a primary miss, retry the lookup under the sibling
 * {@code maven-proxy} / {@code maven} repo_type, and on hit write the value
 * through L1 under the original key so the next call short-circuits.
 *
 * @since 2.2.0
 */
@Testcontainers
final class DbPublishDateRegistryAliasTest {

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
    void gradleProxyLookupHitsHistoricalMavenProxyRow() throws Exception {
        final Instant published = Instant.parse("2026-04-13T08:30:00Z");
        insertRow("maven-proxy", "com.google.guava.guava", "33.6.0-jre", published);
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource shouldNotFire = stubSource("gradle-proxy", "test",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(
            this.ds, Map.of("gradle-proxy", shouldNotFire)
        );

        final Optional<Instant> result = reg.publishDate(
            "gradle-proxy", "com.google.guava.guava", "33.6.0-jre"
        ).get();

        MatcherAssert.assertThat(
            "alias must surface the historical maven-proxy row",
            result, new IsEqual<>(Optional.of(published))
        );
        MatcherAssert.assertThat(
            "alias must short-circuit the upstream source",
            sourceCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    void gradleLookupHitsHistoricalMavenRow() throws Exception {
        final Instant published = Instant.parse("2025-12-01T00:00:00Z");
        insertRow("maven", "org.example.lib", "1.2.3", published);
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of());

        final Optional<Instant> result = reg.publishDate(
            "gradle", "org.example.lib", "1.2.3"
        ).get();

        MatcherAssert.assertThat(result, new IsEqual<>(Optional.of(published)));
    }

    @Test
    void unrelatedRepoTypeDoesNotDriftAcrossAlias() throws Exception {
        // npm-proxy must never alias to anything; an L1+L2 miss for npm-proxy
        // returns empty even if a row with matching (name, version) exists
        // under another repo_type. Belt-and-suspenders against accidental
        // expansion of the alias map in the future.
        insertRow("maven-proxy", "left-pad", "1.3.0", Instant.parse("2020-01-01T00:00:00Z"));
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of());

        final Optional<Instant> result = reg.publishDate(
            "npm-proxy", "left-pad", "1.3.0"
        ).get();

        MatcherAssert.assertThat(result, new IsEqual<>(Optional.<Instant>empty()));
    }

    @Test
    void aliasHitPopulatesL1UnderOriginalKey() throws Exception {
        // First lookup goes through the alias path and pays for the L2 read;
        // the result must be cached under the ORIGINAL repo_type so the next
        // lookup short-circuits on L1 instead of repeating the alias DB hop.
        // To prove the second call hits L1 (not L2 again), we delete the
        // backing row between the two calls — the value must still be served.
        final Instant published = Instant.parse("2026-01-15T12:00:00Z");
        insertRow("maven-proxy", "com.example.lib", "9.9.9", published);
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of());

        final Optional<Instant> first = reg.publishDate(
            "gradle-proxy", "com.example.lib", "9.9.9"
        ).get();
        MatcherAssert.assertThat(
            "first call must surface the aliased row",
            first, new IsEqual<>(Optional.of(published))
        );

        try (Connection conn = this.ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(
                "DELETE FROM artifact_publish_dates "
                + "WHERE repo_type = 'maven-proxy'"
            );
        }

        final Optional<Instant> second = reg.publishDate(
            "gradle-proxy", "com.example.lib", "9.9.9"
        ).get();
        MatcherAssert.assertThat(
            "second call must hit L1 under the original key, not redo alias L2",
            second, new IsEqual<>(Optional.of(published))
        );
    }

    private void insertRow(
        final String repoType, final String name, final String version,
        final Instant when
    ) throws Exception {
        try (Connection conn = this.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO artifact_publish_dates "
                 + "(repo_type, name, version, published_at, source) "
                 + "VALUES (?, ?, ?, ?, 'test')"
             )) {
            ps.setString(1, repoType);
            ps.setString(2, name);
            ps.setString(3, version);
            ps.setTimestamp(4, Timestamp.from(when));
            ps.executeUpdate();
        }
    }

    private PublishDateSource stubSource(
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
