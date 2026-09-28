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
package com.auto1.pantera.index.reindex;

import com.auto1.pantera.backfill.ArtifactRecord;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SQL of {@link JdbcReindexStore} against a real, migrated PostgreSQL.
 *
 * @since 2.2.9
 */
@Testcontainers
final class JdbcReindexStoreIT {

    /**
     * PostgreSQL.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgreSQLTestConfig.createContainer();

    /**
     * Pool.
     */
    private static HikariDataSource source;

    /**
     * Store under test.
     */
    private JdbcReindexStore store;

    @BeforeAll
    static void pool() {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
        cfg.setUsername(POSTGRES.getUsername());
        cfg.setPassword(POSTGRES.getPassword());
        cfg.setMaximumPoolSize(4);
        JdbcReindexStoreIT.source = new HikariDataSource(cfg);
        DbManager.migrate(JdbcReindexStoreIT.source);
    }

    @AfterAll
    static void close() {
        JdbcReindexStoreIT.source.close();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection conn = JdbcReindexStoreIT.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM artifacts");
        }
        this.store = new JdbcReindexStore(JdbcReindexStoreIT.source);
    }

    @Test
    void listsDistinctIndexedRepositories() throws Exception {
        this.row("b-repo", "x", "1", 1L, "alice");
        this.row("a-repo", "x", "1", 1L, "alice");
        this.row("a-repo", "y", "1", 1L, "alice");
        MatcherAssert.assertThat(
            this.store.indexedRepos(), new IsEqual<>(List.of("a-repo", "b-repo"))
        );
    }

    @Test
    void prunesOneBoundedBatchOfTheRepository() throws Exception {
        this.row("qa_gone", "a", "1", 1L, "alice");
        this.row("qa_gone", "b", "1", 1L, "alice");
        this.row("qa_gone", "c", "1", 1L, "alice");
        this.row("live", "a", "1", 1L, "alice");
        MatcherAssert.assertThat(
            "First batch deletes the limit", this.store.pruneBatch("qa_gone", 2), new IsEqual<>(2L)
        );
        MatcherAssert.assertThat(
            "Second batch deletes the rest", this.store.pruneBatch("qa_gone", 2), new IsEqual<>(1L)
        );
        MatcherAssert.assertThat(
            "Other repositories keep their rows", this.names("live"), new IsEqual<>(List.of("a"))
        );
    }

    @Test
    void reconcilesRepositoryAndKeepsUploadMetadata() throws Exception {
        this.row("maven", "kept", "1.0", 100L, "alice");
        this.row("maven", "gone", "1.0", 100L, "alice");
        this.row("maven", "gone.jar.sha1", "1.0", 100L, "alice");
        this.row("other", "gone", "1.0", 100L, "alice");
        final long now = System.currentTimeMillis();
        try (ReindexStore.Session session = this.store.open("maven", now)) {
            MatcherAssert.assertThat(
                "New and changed rows are counted",
                session.upsert(
                    List.of(
                        JdbcReindexStoreIT.record("kept", "1.0", 42L),
                        JdbcReindexStoreIT.record("new", "2.0", 7L)
                    )
                ),
                new IsEqual<>(2L)
            );
            MatcherAssert.assertThat(
                "An unchanged row is not rewritten",
                session.upsert(List.of(JdbcReindexStoreIT.record("kept", "1.0", 42L))),
                new IsEqual<>(0L)
            );
            // a live upload lands during the rebuild
            this.row("maven", "fresh", "1.0", now + 1_000L, "bob");
            MatcherAssert.assertThat(
                "Only the stale primary artifact is removed", session.removeUnseen(10),
                new IsEqual<>(1L)
            );
        }
        MatcherAssert.assertThat(
            "Index holds storage, the checksum row and the live upload",
            this.names("maven"), new IsEqual<>(List.of("fresh", "gone.jar.sha1", "kept", "new"))
        );
        MatcherAssert.assertThat(
            "Existing row keeps owner and creation time, takes the size",
            this.detail("maven", "kept"), new IsEqual<>("alice|100|42")
        );
        MatcherAssert.assertThat(
            "Other repositories are untouched", this.names("other"), new IsEqual<>(List.of("gone"))
        );
    }

    @Test
    void lockIsExclusiveUntilReleased() throws Exception {
        final Optional<ReindexStore.Lease> first = this.store.lock();
        MatcherAssert.assertThat("First lock is taken", first.isPresent(), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "Second lock is refused", this.store.lock().isPresent(), new IsEqual<>(false)
        );
        first.get().close();
        final Optional<ReindexStore.Lease> again = this.store.lock();
        MatcherAssert.assertThat(
            "Lock is free after release", again.isPresent(), new IsEqual<>(true)
        );
        again.get().close();
    }

    /**
     * Insert a row.
     * @param repo Repository
     * @param name Name
     * @param version Version
     * @param created Creation time
     * @param owner Owner
     * @throws Exception On failure
     */
    private void row(final String repo, final String name, final String version,
        final long created, final String owner) throws Exception {
        try (Connection conn = JdbcReindexStoreIT.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date, owner)"
                    + " VALUES ('maven', ?, ?, ?, 1, ?, ?)"
            )) {
            stmt.setString(1, repo);
            stmt.setString(2, name);
            stmt.setString(3, version);
            stmt.setLong(4, created);
            stmt.setString(5, owner);
            stmt.executeUpdate();
        }
    }

    /**
     * Sorted names of a repository.
     * @param repo Repository
     * @return Names
     * @throws Exception On failure
     */
    private List<String> names(final String repo) throws Exception {
        final List<String> names = new ArrayList<>();
        try (Connection conn = JdbcReindexStoreIT.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT name FROM artifacts WHERE repo_name = ? ORDER BY name"
            )) {
            stmt.setString(1, repo);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    /**
     * Owner, creation time and size of a row.
     * @param repo Repository
     * @param name Name
     * @return "owner|created|size"
     * @throws Exception On failure
     */
    private String detail(final String repo, final String name) throws Exception {
        try (Connection conn = JdbcReindexStoreIT.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT owner, created_date, size FROM artifacts WHERE repo_name = ? AND name = ?"
            )) {
            stmt.setString(1, repo);
            stmt.setString(2, name);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return String.join(
                    "|", rs.getString(1), String.valueOf(rs.getLong(2)),
                    String.valueOf(rs.getLong(3))
                );
            }
        }
    }

    /**
     * Scanner-shaped record.
     * @param name Name
     * @param version Version
     * @param size Size
     * @return Record
     */
    private static ArtifactRecord record(final String name, final String version, final long size) {
        return new ArtifactRecord(
            "maven", "maven", name, version, size, 5L, null, "system", null
        );
    }
}
