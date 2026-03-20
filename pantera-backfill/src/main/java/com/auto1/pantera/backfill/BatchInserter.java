/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.backfill;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batches artifact records and inserts them into PostgreSQL using JDBC batch
 * operations. Supports dry-run mode where records are counted but not
 * persisted to the database.
 *
 * <p>On first call the {@code artifacts} table and its indexes are created
 * if they do not already exist.</p>
 *
 * <p>When a batch commit fails the inserter falls back to individual inserts
 * so that a single bad record does not block the entire batch.</p>
 *
 * @since 1.20.13
 */
public final class BatchInserter implements AutoCloseable {

    /**
     * SLF4J logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(BatchInserter.class);

    /**
     * UPSERT SQL — must match DbConsumer parameter binding order exactly.
     */
    private static final String UPSERT_SQL = String.join(
        " ",
        "INSERT INTO artifacts",
        "(repo_type, repo_name, name, version, size,",
        "created_date, release_date, owner, path_prefix)",
        "VALUES (?,?,?,?,?,?,?,?,?)",
        "ON CONFLICT (repo_name, name, version)",
        "DO UPDATE SET repo_type = EXCLUDED.repo_type,",
        "size = EXCLUDED.size,",
        "created_date = EXCLUDED.created_date,",
        "release_date = EXCLUDED.release_date,",
        "owner = EXCLUDED.owner,",
        "path_prefix = COALESCE(EXCLUDED.path_prefix, artifacts.path_prefix)"
    );

    /**
     * JDBC data source.
     */
    private final DataSource source;

    /**
     * Maximum number of records per batch.
     */
    private final int batchSize;

    /**
     * When {@code true} records are counted but not written to the database.
     */
    private final boolean dryRun;

    /**
     * Buffer of records awaiting the next flush.
     */
    private final List<ArtifactRecord> buffer;

    /**
     * Total records successfully inserted (or counted in dry-run mode).
     */
    private final AtomicLong insertedCount;

    /**
     * Total records that could not be inserted.
     */
    private final AtomicLong skippedCount;

    /**
     * Whether the table DDL has already been executed in this session.
     */
    private boolean tableCreated;

    /**
     * Ctor.
     *
     * @param source JDBC data source
     * @param batchSize Maximum records per batch flush
     * @param dryRun If {@code true}, count only — no DB writes
     */
    public BatchInserter(final DataSource source, final int batchSize,
        final boolean dryRun) {
        this.source = source;
        this.batchSize = batchSize;
        this.dryRun = dryRun;
        this.buffer = new ArrayList<>(batchSize);
        this.insertedCount = new AtomicLong(0L);
        this.skippedCount = new AtomicLong(0L);
        this.tableCreated = false;
    }

    /**
     * Accept a single artifact record. The record is buffered internally
     * and flushed automatically when the buffer reaches {@code batchSize}.
     *
     * @param record Artifact record to insert
     */
    public void accept(final ArtifactRecord record) {
        this.buffer.add(record);
        if (this.buffer.size() >= this.batchSize) {
            this.flush();
        }
    }

    /**
     * Flush all buffered records to the database (or count them in dry-run).
     */
    public void flush() {
        if (this.buffer.isEmpty()) {
            return;
        }
        if (this.dryRun) {
            this.insertedCount.addAndGet(this.buffer.size());
            LOG.info("[dry-run] Would insert {} records (total: {})",
                this.buffer.size(), this.insertedCount.get());
            this.buffer.clear();
            return;
        }
        this.ensureTable();
        final List<ArtifactRecord> batch = new ArrayList<>(this.buffer);
        this.buffer.clear();
        try (Connection conn = this.source.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
                for (final ArtifactRecord rec : batch) {
                    bindRecord(stmt, rec);
                    stmt.addBatch();
                }
                stmt.executeBatch();
                conn.commit();
                this.insertedCount.addAndGet(batch.size());
            } catch (final SQLException ex) {
                rollback(conn);
                LOG.warn("Batch insert of {} records failed, falling back to "
                    + "individual inserts: {}", batch.size(), ex.getMessage());
                this.insertIndividually(batch);
            }
        } catch (final SQLException ex) {
            LOG.warn("Failed to obtain DB connection for batch of {} records: {}",
                batch.size(), ex.getMessage());
            this.skippedCount.addAndGet(batch.size());
        }
    }

    /**
     * Return total number of successfully inserted records.
     *
     * @return Inserted count
     */
    public long getInsertedCount() {
        return this.insertedCount.get();
    }

    /**
     * Return total number of records that were skipped due to errors.
     *
     * @return Skipped count
     */
    public long getSkippedCount() {
        return this.skippedCount.get();
    }

    @Override
    public void close() {
        this.flush();
        LOG.info("BatchInserter closed — inserted: {}, skipped: {}",
            this.insertedCount.get(), this.skippedCount.get());
    }

    /**
     * Ensure the artifacts table and performance indexes exist.
     * Called once per session on the first real flush.
     */
    private void ensureTable() {
        if (this.tableCreated) {
            return;
        }
        try (Connection conn = this.source.getConnection();
            Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                String.join(
                    "\n",
                    "CREATE TABLE IF NOT EXISTS artifacts(",
                    "   id BIGSERIAL PRIMARY KEY,",
                    "   repo_type VARCHAR NOT NULL,",
                    "   repo_name VARCHAR NOT NULL,",
                    "   name VARCHAR NOT NULL,",
                    "   version VARCHAR NOT NULL,",
                    "   size BIGINT NOT NULL,",
                    "   created_date BIGINT NOT NULL,",
                    "   release_date BIGINT,",
                    "   owner VARCHAR NOT NULL,",
                    "   UNIQUE (repo_name, name, version)",
                    ");"
                )
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_repo_lookup "
                    + "ON artifacts(repo_name, name, version)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_repo_type_name "
                    + "ON artifacts(repo_type, repo_name, name)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_created_date "
                    + "ON artifacts(created_date)"
            );
            stmt.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_artifacts_owner "
                    + "ON artifacts(owner)"
            );
            this.tableCreated = true;
            LOG.info("Artifacts table and indexes verified/created");
        } catch (final SQLException ex) {
            LOG.warn("Failed to create artifacts table: {}", ex.getMessage());
        }
    }

    /**
     * Fall back to inserting records one by one after a batch failure.
     *
     * @param records Records to insert individually
     */
    private void insertIndividually(final List<ArtifactRecord> records) {
        for (final ArtifactRecord rec : records) {
            try (Connection conn = this.source.getConnection();
                PreparedStatement stmt = conn.prepareStatement(UPSERT_SQL)) {
                conn.setAutoCommit(false);
                bindRecord(stmt, rec);
                stmt.executeUpdate();
                conn.commit();
                this.insertedCount.incrementAndGet();
            } catch (final SQLException ex) {
                LOG.warn("Individual insert failed for {}/{}:{} — {}",
                    rec.repoName(), rec.name(), rec.version(),
                    ex.getMessage());
                this.skippedCount.incrementAndGet();
            }
        }
    }

    /**
     * Bind an {@link ArtifactRecord} to a {@link PreparedStatement}.
     * Parameter order must match the UPSERT_SQL and DbConsumer exactly.
     *
     * @param stmt Prepared statement
     * @param rec Artifact record
     * @throws SQLException On binding error
     */
    private static void bindRecord(final PreparedStatement stmt,
        final ArtifactRecord rec) throws SQLException {
        stmt.setString(1, rec.repoType());
        stmt.setString(2, rec.repoName() == null
            ? null : rec.repoName().trim());
        stmt.setString(3, rec.name());
        stmt.setString(4, rec.version());
        stmt.setLong(5, rec.size());
        stmt.setLong(6, rec.createdDate());
        if (rec.releaseDate() == null) {
            stmt.setNull(7, Types.BIGINT);
        } else {
            stmt.setLong(7, rec.releaseDate());
        }
        stmt.setString(8, rec.owner());
        if (rec.pathPrefix() == null) {
            stmt.setNull(9, Types.VARCHAR);
        } else {
            stmt.setString(9, rec.pathPrefix());
        }
    }

    /**
     * Attempt to rollback the current transaction, logging any failure.
     *
     * @param conn JDBC connection
     */
    private static void rollback(final Connection conn) {
        try {
            conn.rollback();
        } catch (final SQLException ex) {
            LOG.warn("Rollback failed: {}", ex.getMessage());
        }
    }
}
