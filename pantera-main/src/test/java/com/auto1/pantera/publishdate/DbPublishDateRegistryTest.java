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
    void sourceFailureReturnsEmptyAndDoesNotCache() throws Exception {
        final AtomicInteger sourceCalls = new AtomicInteger();
        final PublishDateSource flaky = stubSource("maven", "fake",
            (n, v) -> {
                sourceCalls.incrementAndGet();
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            });
        final DbPublishDateRegistry reg = new DbPublishDateRegistry(this.ds, Map.of("maven", flaky));

        assertEquals(Optional.empty(), reg.publishDate("maven", "x.y", "1.0").get());
        assertEquals(Optional.empty(), reg.publishDate("maven", "x.y", "1.0").get());
        assertEquals(2, sourceCalls.get(), "transient failure must not be cached");
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
