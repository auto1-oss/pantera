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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * PostgreSQL implementation of {@link ReindexStore} over the
 * {@code artifacts} table.
 *
 * <p>Deletes pick their victims in a CTE ordered by (name, version) with
 * {@code FOR UPDATE SKIP LOCKED}: rows a live writer is holding are left
 * alone (they are being refreshed right now) and the lock order matches
 * DbConsumer's, so the rebuild cannot deadlock with it.</p>
 *
 * @since 2.2.9
 */
public final class JdbcReindexStore implements ReindexStore {

    /**
     * Advisory lock key of the rebuild ("PNTRIDX" as ASCII).
     */
    private static final long LOCK_KEY = 0x504e5452494458L;

    /**
     * Distinct indexed repository names (loose index scan over the
     * (repo_name, name, version) unique index).
     */
    private static final String REPOS_SQL = String.join(
        " ",
        "WITH RECURSIVE r AS (",
        "SELECT MIN(repo_name) AS n FROM artifacts",
        "UNION ALL",
        "SELECT (SELECT MIN(repo_name) FROM artifacts WHERE repo_name > r.n)",
        "FROM r WHERE r.n IS NOT NULL",
        ") SELECT n FROM r WHERE n IS NOT NULL"
    );

    /**
     * One prune batch.
     */
    private static final String PRUNE_SQL = String.join(
        " ",
        "WITH doomed AS (",
        "SELECT id FROM artifacts WHERE repo_name = ?",
        "ORDER BY name, version LIMIT ? FOR UPDATE SKIP LOCKED",
        ") DELETE FROM artifacts WHERE id IN (SELECT id FROM doomed)"
    );

    /**
     * Rebuild upsert. Parameter order matches DbConsumer / BatchInserter.
     * An existing row keeps its owner, creation time and repository type
     * (written by the upload path, which knows them better than a disk
     * scan); size and storage key are refreshed, a missing release date is
     * filled. Unchanged rows are not rewritten.
     */
    private static final String UPSERT_SQL = String.join(
        " ",
        "INSERT INTO artifacts",
        "(repo_type, repo_name, name, version, size, created_date, release_date, owner, path_prefix)",
        "VALUES (?,?,?,?,?,?,?,?,?)",
        "ON CONFLICT (repo_name, name, version) DO UPDATE SET",
        "size = EXCLUDED.size,",
        "release_date = COALESCE(artifacts.release_date, EXCLUDED.release_date),",
        "path_prefix = COALESCE(EXCLUDED.path_prefix, artifacts.path_prefix)",
        "WHERE artifacts.size IS DISTINCT FROM EXCLUDED.size",
        "OR (artifacts.release_date IS NULL AND EXCLUDED.release_date IS NOT NULL)",
        "OR (EXCLUDED.path_prefix IS NOT NULL",
        "AND artifacts.path_prefix IS DISTINCT FROM EXCLUDED.path_prefix)"
    );

    /**
     * Session temp table of the keys the scan produced.
     */
    private static final String SEEN_DDL = String.join(
        " ",
        "CREATE TEMP TABLE IF NOT EXISTS reindex_seen",
        "(name VARCHAR NOT NULL, version VARCHAR NOT NULL, PRIMARY KEY (name, version))"
    );

    /**
     * Mark a key as seen.
     */
    private static final String SEEN_SQL =
        "INSERT INTO reindex_seen (name, version) VALUES (?, ?) ON CONFLICT DO NOTHING";

    /**
     * One batch of rows the scan did not produce. Only primary artifacts
     * are reconciled: checksum, signature and metadata rows are written by
     * the upload paths and no scanner emits them.
     */
    private static final String REMOVE_SQL = String.join(
        " ",
        "WITH doomed AS (",
        "SELECT a.id FROM artifacts a",
        "WHERE a.repo_name = ? AND a.id <= ? AND a.created_date < ?",
        "AND a.artifact_kind = 'ARTIFACT'",
        "AND NOT EXISTS (SELECT 1 FROM reindex_seen s",
        "WHERE s.name = a.name AND s.version = a.version)",
        "ORDER BY a.name, a.version LIMIT ? FOR UPDATE OF a SKIP LOCKED",
        ") DELETE FROM artifacts WHERE id IN (SELECT id FROM doomed)"
    );

    /**
     * Deadlock SQLSTATE.
     */
    private static final String DEADLOCK = "40P01";

    /**
     * Upsert attempts on deadlock.
     */
    private static final int ATTEMPTS = 3;

    /**
     * Data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Data source
     */
    public JdbcReindexStore(final DataSource source) {
        this.source = source;
    }

    @Override
    public Optional<Lease> lock() throws SQLException {
        // The connection outlives this method while the lock is held: a
        // session-level advisory lock belongs to its connection.
        final Connection conn = this.source.getConnection();
        boolean held = false;
        try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_try_advisory_lock(?)"); // NOPMD UseTryWithResources - conn is closed by the lease

            ResultSet rs = JdbcReindexStore.query(stmt, JdbcReindexStore.LOCK_KEY)) {
            if (rs.next()) {
                held = rs.getBoolean(1);
            }
        } finally {
            if (!held) {
                conn.close();
            }
        }
        final Optional<Lease> res;
        if (held) {
            res = Optional.of(() -> JdbcReindexStore.unlock(conn));
        } else {
            res = Optional.empty();
        }
        return res;
    }

    @Override
    public List<String> indexedRepos() throws SQLException {
        final List<String> names = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(JdbcReindexStore.REPOS_SQL)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    @Override
    public long pruneBatch(final String repo, final int limit) throws SQLException {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(JdbcReindexStore.PRUNE_SQL)) {
            stmt.setString(1, repo);
            stmt.setInt(2, limit);
            return stmt.executeUpdate();
        }
    }

    @Override
    public Session open(final String repo, final long startedMillis) throws SQLException {
        final Connection conn = this.source.getConnection();
        try {
            conn.setAutoCommit(true);
            final long maxId;
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(JdbcReindexStore.SEEN_DDL);
                stmt.execute("TRUNCATE reindex_seen");
                try (ResultSet rs = stmt.executeQuery("SELECT COALESCE(MAX(id), 0) FROM artifacts")) {
                    if (rs.next()) {
                        maxId = rs.getLong(1);
                    } else {
                        maxId = 0L;
                    }
                }
            }
            return new JdbcSession(conn, repo, maxId, startedMillis);
        } catch (final SQLException ex) {
            conn.close();
            throw ex;
        }
    }

    /**
     * Run a query with one long parameter.
     * @param stmt Statement
     * @param value Parameter
     * @return Result set
     * @throws SQLException On database error
     */
    private static ResultSet query(final PreparedStatement stmt, final long value)
        throws SQLException {
        stmt.setLong(1, value);
        return stmt.executeQuery();
    }

    /**
     * Release the advisory lock and return the connection.
     * @param conn Connection holding the lock
     */
    private static void unlock(final Connection conn) {
        try (conn; PreparedStatement stmt = conn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            stmt.setLong(1, JdbcReindexStore.LOCK_KEY);
            stmt.execute();
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to release the reindex lock", ex);
        }
    }

    /**
     * Reconcile session on one pooled connection (the temp table is
     * connection-scoped).
     * @since 2.2.9
     */
    private static final class JdbcSession implements Session {

        /**
         * Connection.
         */
        private final Connection conn;

        /**
         * Repository name.
         */
        private final String repo;

        /**
         * Highest row id when the session opened.
         */
        private final long maxId;

        /**
         * Rebuild start of the repository.
         */
        private final long started;

        /**
         * Ctor.
         * @param conn Connection
         * @param repo Repository name
         * @param maxId Highest row id at open
         * @param started Rebuild start, epoch millis
         */
        JdbcSession(final Connection conn, final String repo, final long maxId,
            final long started) {
            this.conn = conn;
            this.repo = repo;
            this.maxId = maxId;
            this.started = started;
        }

        @Override
        public long upsert(final List<ArtifactRecord> batch) throws SQLException {
            SQLException last = null;
            for (int attempt = 0; attempt < JdbcReindexStore.ATTEMPTS; attempt += 1) {
                try {
                    return this.upsertOnce(batch);
                } catch (final SQLException ex) {
                    if (!JdbcReindexStore.DEADLOCK.equals(ex.getSQLState())) {
                        throw ex;
                    }
                    last = ex;
                }
            }
            throw last;
        }

        @Override
        public long removeUnseen(final int limit) throws SQLException {
            try (PreparedStatement stmt = this.conn.prepareStatement(JdbcReindexStore.REMOVE_SQL)) {
                stmt.setString(1, this.repo);
                stmt.setLong(2, this.maxId);
                stmt.setLong(3, this.started);
                stmt.setInt(4, limit);
                return stmt.executeUpdate();
            }
        }

        @Override
        public void close() {
            try (Connection closing = this.conn; Statement stmt = closing.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS pg_temp.reindex_seen");
            } catch (final SQLException ex) {
                throw new IllegalStateException("Failed to close the reindex session", ex);
            }
        }

        /**
         * Upsert one batch in one transaction.
         * @param batch Sorted records
         * @return Rows inserted or changed
         * @throws SQLException On database error
         */
        private long upsertOnce(final List<ArtifactRecord> batch) throws SQLException {
            this.conn.setAutoCommit(false);
            try (PreparedStatement upsert = this.conn.prepareStatement(JdbcReindexStore.UPSERT_SQL);
                PreparedStatement seen = this.conn.prepareStatement(JdbcReindexStore.SEEN_SQL)) {
                for (final ArtifactRecord rec : batch) {
                    this.bind(upsert, rec);
                    upsert.addBatch();
                    seen.setString(1, rec.name());
                    seen.setString(2, rec.version());
                    seen.addBatch();
                }
                long rows = 0L;
                for (final int count : upsert.executeBatch()) {
                    rows += Math.max(count, 0);
                }
                seen.executeBatch();
                this.conn.commit();
                return rows;
            } catch (final SQLException ex) {
                this.conn.rollback();
                throw ex;
            } finally {
                this.conn.setAutoCommit(true);
            }
        }

        /**
         * Bind a record, forced into this session's repository.
         * @param stmt Upsert statement
         * @param rec Record
         * @throws SQLException On binding error
         */
        private void bind(final PreparedStatement stmt, final ArtifactRecord rec)
            throws SQLException {
            stmt.setString(1, rec.repoType());
            stmt.setString(2, this.repo);
            stmt.setString(3, rec.name());
            stmt.setString(4, rec.version());
            stmt.setLong(5, rec.size());
            stmt.setLong(6, rec.createdDate());
            if (rec.releaseDate() == null) {
                stmt.setNull(7, Types.BIGINT);
            } else {
                stmt.setLong(7, rec.releaseDate());
            }
            stmt.setString(8, rec.owner() == null ? "system" : rec.owner());
            if (rec.pathPrefix() == null) {
                stmt.setNull(9, Types.VARCHAR);
            } else {
                stmt.setString(9, rec.pathPrefix());
            }
        }
    }
}
