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
package com.auto1.pantera.db;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.scheduling.ArtifactEvent;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the Phase A bridge from cache-write events into the canonical
 * {@code artifact_publish_dates} table. When an {@link ArtifactEvent} carries
 * a release date, the {@link DbConsumer} must populate both {@code artifacts}
 * and {@code artifact_publish_dates}.
 *
 * @since 2.2.0
 */
@Testcontainers
class DbConsumerPublishDateBridgeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    private DataSource source;

    @BeforeEach
    void init() throws SQLException {
        this.source = new ArtifactDbFactory(
            Yaml.createYamlMappingBuilder().add(
                "artifacts_database",
                Yaml.createYamlMappingBuilder()
                    .add(ArtifactDbFactory.YAML_HOST, POSTGRES.getHost())
                    .add(ArtifactDbFactory.YAML_PORT, String.valueOf(POSTGRES.getFirstMappedPort()))
                    .add(ArtifactDbFactory.YAML_DATABASE, POSTGRES.getDatabaseName())
                    .add(ArtifactDbFactory.YAML_USER, POSTGRES.getUsername())
                    .add(ArtifactDbFactory.YAML_PASSWORD, POSTGRES.getPassword())
                    .build()
            ).build(),
            "artifacts"
        ).initialize();
        try (Connection conn = this.source.getConnection();
             Statement stmt = conn.createStatement()) {
            // Bootstrap the table that V125 creates in production; this unit
            // test bypasses Flyway and only uses ArtifactDbFactory.initialize().
            stmt.execute(String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS artifact_publish_dates(",
                "    repo_type    VARCHAR(32)  NOT NULL,",
                "    name         VARCHAR(512) NOT NULL,",
                "    version      VARCHAR(128) NOT NULL,",
                "    published_at TIMESTAMPTZ  NOT NULL,",
                "    source       VARCHAR(64)  NOT NULL,",
                "    fetched_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),",
                "    PRIMARY KEY (repo_type, name, version)",
                ")"
            ));
            stmt.execute("DELETE FROM artifacts");
            stmt.execute("DELETE FROM artifact_publish_dates");
        }
    }

    @Test
    void bridgesReleaseDateIntoArtifactPublishDates() throws InterruptedException {
        final DbConsumer consumer = new DbConsumer(this.source);
        Thread.sleep(500);
        final long created = System.currentTimeMillis();
        final long releaseMillis = created - 24L * 60L * 60L * 1000L;
        final ArtifactEvent event = new ArtifactEvent(
            "maven-proxy", "central", "Alice",
            "com.example.lib", "1.0.0", 1250L, created, releaseMillis
        );
        consumer.accept(event);
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> {
            try (
                Connection conn = this.source.getConnection();
                Statement stat = conn.createStatement()
            ) {
                stat.execute(
                    "SELECT COUNT(*) FROM artifact_publish_dates "
                        + "WHERE repo_type = 'maven-proxy' "
                        + "AND name = 'com.example.lib' AND version = '1.0.0'"
                );
                final ResultSet rs = stat.getResultSet();
                rs.next();
                return rs.getInt(1) == 1;
            }
        });
        try (
            Connection conn = this.source.getConnection();
            Statement stat = conn.createStatement()
        ) {
            stat.execute(
                "SELECT source, EXTRACT(EPOCH FROM published_at) * 1000 AS pub_millis "
                    + "FROM artifact_publish_dates "
                    + "WHERE repo_type = 'maven-proxy' "
                    + "AND name = 'com.example.lib' AND version = '1.0.0'"
            );
            final ResultSet res = stat.getResultSet();
            res.next();
            MatcherAssert.assertThat(
                "source must mark cache-write origin",
                res.getString("source"), new IsEqual<>("cache_write_event")
            );
            final long observed = (long) res.getDouble("pub_millis");
            // Allow a 5ms drift from millisecond-truncation in TIMESTAMPTZ.
            MatcherAssert.assertThat(
                "published_at must round-trip the event release_date",
                Math.abs(observed - releaseMillis) <= 5L,
                new IsEqual<>(true)
            );
        } catch (final SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void skipsBridgeWhenReleaseDateAbsent() throws InterruptedException {
        final DbConsumer consumer = new DbConsumer(this.source);
        Thread.sleep(500);
        final long created = System.currentTimeMillis();
        final ArtifactEvent event = new ArtifactEvent(
            "rpm", "my-rpm", "Bob", "org.test", "9.9", 1L, created
        );
        consumer.accept(event);
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> {
            try (
                Connection conn = this.source.getConnection();
                Statement stat = conn.createStatement()
            ) {
                stat.execute(
                    "SELECT COUNT(*) FROM artifacts "
                        + "WHERE name = 'org.test' AND version = '9.9'"
                );
                final ResultSet rs = stat.getResultSet();
                rs.next();
                return rs.getInt(1) == 1;
            }
        });
        try (
            Connection conn = this.source.getConnection();
            Statement stat = conn.createStatement()
        ) {
            stat.execute(
                "SELECT COUNT(*) FROM artifact_publish_dates "
                    + "WHERE name = 'org.test' AND version = '9.9'"
            );
            final ResultSet rs = stat.getResultSet();
            rs.next();
            MatcherAssert.assertThat(
                rs.getInt(1), new IsEqual<>(0)
            );
        } catch (final SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
