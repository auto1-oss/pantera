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
package com.auto1.pantera.repojob;

import com.auto1.pantera.db.DbManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Database helper for the repository lifecycle tests: a migrated schema,
 * emptied between tests, and one-line reads and writes.
 *
 * @since 2.2.14
 */
final class RepoJobsDb implements AutoCloseable {

    /**
     * Pool.
     */
    private final HikariDataSource pool;

    /**
     * Ctor.
     * @param container Started PostgreSQL container
     */
    RepoJobsDb(final PostgreSQLContainer<?> container) {
        final HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(container.getJdbcUrl());
        cfg.setUsername(container.getUsername());
        cfg.setPassword(container.getPassword());
        cfg.setMaximumPoolSize(6);
        this.pool = new HikariDataSource(cfg);
        DbManager.migrate(this.pool);
    }

    /**
     * Data source.
     * @return Data source
     */
    DataSource source() {
        return this.pool;
    }

    /**
     * Empty every table the lifecycle touches.
     */
    void clean() {
        this.exec(
            "TRUNCATE repo_jobs, repository_tombstones, repository_aliases, cooldown_exemptions,"
                + " repositories, storage_aliases, artifact_cooldowns, artifact_cooldowns_history,"
                + " import_sessions, artifacts RESTART IDENTITY CASCADE"
        );
        this.exec("DELETE FROM roles WHERE name LIKE 'test-%'");
    }

    /**
     * Create a repository with local storage.
     * @param name Repository name
     * @param type Repository type
     */
    void repo(final String name, final String type) {
        this.exec(
            "INSERT INTO repositories (name, type, config) VALUES (?, ?, ?::jsonb)",
            name, type,
            String.format(
                "{\"repo\":{\"type\":\"%s\",\"storage\":{\"type\":\"fs\",\"path\":\"/var/pantera/data\"}}}",
                type
            )
        );
    }

    /**
     * Create a group repository.
     * @param name Group name
     * @param members Member names
     */
    void group(final String name, final String... members) {
        final StringBuilder list = new StringBuilder();
        for (final String member : members) {
            if (list.length() > 0) {
                list.append(',');
            }
            list.append('"').append(member).append('"');
        }
        this.exec(
            "INSERT INTO repositories (name, type, config) VALUES (?, 'maven-group', ?::jsonb)",
            name, String.format("{\"repo\":{\"type\":\"maven-group\",\"members\":[%s]}}", list)
        );
    }

    /**
     * Add an index row.
     * @param key Repository key (the value of artifacts.repo_name)
     * @param artifact Artifact name
     * @param version Version
     */
    void artifact(final String key, final String artifact, final String version) {
        this.exec(
            "INSERT INTO artifacts (repo_type, repo_name, name, version, size, created_date,"
                + " release_date, owner) VALUES ('maven', ?, ?, ?, 1, 1, 1, 'tester')",
            key, artifact, version
        );
    }

    /**
     * Run a statement with string parameters.
     * @param sql Statement
     * @param params Parameters
     */
    void exec(final String sql, final String... params) {
        try (Connection conn = this.pool.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int idx = 0; idx < params.length; idx += 1) {
                stmt.setString(idx + 1, params[idx]);
            }
            stmt.execute();
        } catch (final SQLException ex) {
            throw new IllegalStateException(sql, ex);
        }
    }

    /**
     * First column of every row of a query.
     * @param sql Query
     * @param params String parameters
     * @return Values as strings ({@code null} for SQL NULL)
     */
    List<String> column(final String sql, final String... params) {
        final List<String> res = new ArrayList<>();
        try (Connection conn = this.pool.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int idx = 0; idx < params.length; idx += 1) {
                stmt.setString(idx + 1, params[idx]);
            }
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    res.add(rows.getString(1));
                }
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException(sql, ex);
        }
        return res;
    }

    /**
     * Single value of a query.
     * @param sql Query
     * @param params String parameters
     * @return Value as string, {@code null} when there is no row
     */
    String value(final String sql, final String... params) {
        final List<String> all = this.column(sql, params);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Row count of a query.
     * @param sql Count query
     * @param params String parameters
     * @return Count
     */
    long count(final String sql, final String... params) {
        return Long.parseLong(this.value(sql, params));
    }

    @Override
    public void close() {
        try (Connection conn = this.pool.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (final SQLException ignored) {
            // closing anyway
        }
        this.pool.close();
    }
}
