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

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;

/**
 * Access to {@code repo_jobs}.
 *
 * <p>A node works on a job only while it holds the job's lease. Every
 * progress write is conditional on the lease, so a node that lost it (it
 * stalled and another node took over, or the delete was restored) learns so
 * on its next write and stops. A job whose holder died is claimed again
 * when the lease runs out and resumes from its checkpoint.</p>
 *
 * @since 2.2.14
 */
public final class RepoJobDao {

    /**
     * Failed attempts after which a job stops retrying on its own.
     */
    static final int MAX_ATTEMPTS = 8;

    /**
     * Columns of a job row.
     */
    private static final String COLUMNS = String.join(
        ", ",
        "id", "type", "repo_key", "repo_name", "state", "phase", "params", "checkpoint",
        "objects_done", "rows_done", "attempts", "last_error", "not_before", "lease_owner",
        "created_by", "trace_id", "created_at", "updated_at", "finished_at",
        "(EXTRACT(EPOCH FROM (NOW() - created_at)) * 1000)::bigint AS age_ms"
    );

    /**
     * Database.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database
     */
    public RepoJobDao(final DataSource source) {
        this.source = source;
    }

    /**
     * Queue a job inside the caller's transaction. Every time is taken from
     * the database clock: nodes and the database need not agree on the time.
     * @param conn Connection
     * @param type Kind of work
     * @param key Repository key
     * @param name Repository name
     * @param params Job parameters
     * @param delay Delay before the job may start
     * @param actor Who starts it, nullable
     * @param trace Trace id, nullable
     * @return Job id and its earliest start
     * @throws SQLException On error, including a live purge of the same key
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    static Queued insert(
        final Connection conn, final RepoJob.Type type, final String key, final String name,
        final JsonObject params, final Duration delay, final String actor, final String trace
    ) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "INSERT INTO repo_jobs (type, repo_key, repo_name, params, not_before, created_by, trace_id)"
                + " VALUES (?, ?, ?, ?::jsonb, NOW() + (? * INTERVAL '1 millisecond'), ?, ?)"
                + " RETURNING id, not_before"
        )) {
            stmt.setString(1, type.db());
            stmt.setString(2, key);
            stmt.setString(3, name);
            stmt.setString(4, params.toString());
            stmt.setLong(5, delay.toMillis());
            stmt.setString(6, actor);
            stmt.setString(7, trace);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("INSERT INTO repo_jobs returned no row");
                }
                return new Queued(rows.getLong(1), rows.getTimestamp(2).toInstant());
            }
        }
    }

    /**
     * A queued job.
     * @param id Job id
     * @param start Earliest start, by the database clock
     */
    record Queued(long id, Instant start) {
    }

    /**
     * Queue a job.
     * @param type Kind of work
     * @param key Repository key
     * @param name Repository name
     * @param params Job parameters
     * @param actor Who starts it, nullable
     * @param trace Trace id, nullable
     * @return Job id
     * @throws RepoOpRejected When a purge of the same key is already queued
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public long enqueue(
        final RepoJob.Type type, final String key, final String name,
        final JsonObject params, final String actor, final String trace
    ) {
        try (Connection conn = this.source.getConnection()) {
            return RepoJobDao.insert(conn, type, key, name, params, Duration.ZERO, actor, trace).id();
        } catch (final SQLException ex) {
            if ("23505".equals(ex.getSQLState())) {
                throw new RepoOpRejected(
                    409, "CONFLICT", String.format("A purge of '%s' is already queued", key), ex
                );
            }
            throw new IllegalStateException("Failed to queue repository job", ex);
        }
    }

    /**
     * Take the lease of the oldest job that is due and that no live node
     * holds. A job that has not started moves to its first phase.
     * @param owner This node
     * @param lease Lease length
     * @return The claimed job, empty when nothing is due
     */
    public Optional<RepoJob> claim(final String owner, final Duration lease) {
        final String sql = String.join(
            " ",
            "UPDATE repo_jobs SET state = 'running', lease_owner = ?,",
            "lease_until = NOW() + (? * INTERVAL '1 millisecond'), updated_at = NOW(),",
            "phase = CASE WHEN phase <> 'waiting' THEN phase",
            "WHEN type = 'rename_followup' THEN 'rows' ELSE 'objects' END",
            "WHERE id = (SELECT id FROM repo_jobs",
            "WHERE state IN ('pending', 'running') AND not_before <= NOW()",
            "AND (lease_until IS NULL OR lease_until < NOW())",
            "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)",
            "RETURNING", RepoJobDao.COLUMNS
        );
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, owner);
            stmt.setLong(2, lease.toMillis());
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next() ? Optional.of(RepoJobDao.job(rows)) : Optional.empty();
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to claim a repository job", ex);
        }
    }

    /**
     * Record progress and renew the lease.
     * @param id Job id
     * @param owner This node
     * @param lease Lease length
     * @param phase Phase the job is in
     * @param checkpoint Resume position inside the phase, nullable
     * @param objects Objects removed since the last write
     * @param rows Rows removed or rewritten since the last write
     * @return False when this node no longer holds the job: stop working
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public boolean progress(
        final long id, final String owner, final Duration lease, final String phase,
        final String checkpoint, final long objects, final long rows
    ) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE repo_jobs SET phase = ?, checkpoint = ?, objects_done = objects_done + ?,"
                    + " rows_done = rows_done + ?, updated_at = NOW(),"
                    + " lease_until = NOW() + (? * INTERVAL '1 millisecond')"
                    + " WHERE id = ? AND lease_owner = ? AND state = 'running'"
            )) {
            stmt.setString(1, phase);
            stmt.setString(2, checkpoint);
            stmt.setLong(3, objects);
            stmt.setLong(4, rows);
            stmt.setLong(5, lease.toMillis());
            stmt.setLong(6, id);
            stmt.setString(7, owner);
            return stmt.executeUpdate() == 1;
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to record repository job progress", ex);
        }
    }

    /**
     * Renew the lease without recording progress (a slow storage call).
     * @param id Job id
     * @param owner This node
     * @param lease Lease length
     * @return False when this node no longer holds the job
     */
    public boolean renew(final long id, final String owner, final Duration lease) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE repo_jobs SET lease_until = NOW() + (? * INTERVAL '1 millisecond')"
                    + " WHERE id = ? AND lease_owner = ? AND state = 'running'"
            )) {
            stmt.setLong(1, lease.toMillis());
            stmt.setLong(2, id);
            stmt.setString(3, owner);
            return stmt.executeUpdate() == 1;
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to renew repository job lease", ex);
        }
    }

    /**
     * Mark a job finished.
     * @param id Job id
     * @param owner This node
     * @return False when this node no longer holds the job
     */
    public boolean done(final long id, final String owner) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE repo_jobs SET state = 'done', finished_at = NOW(), updated_at = NOW(),"
                    + " lease_owner = NULL, lease_until = NULL, last_error = NULL"
                    + " WHERE id = ? AND lease_owner = ? AND state = 'running'"
            )) {
            stmt.setLong(1, id);
            stmt.setString(2, owner);
            return stmt.executeUpdate() == 1;
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to finish repository job", ex);
        }
    }

    /**
     * Give a job back after a failure: it is retried after the backoff, or
     * parked as failed once it has failed {@link #MAX_ATTEMPTS} times. A
     * throttled storage is not a failure: {@code counts} false keeps the
     * attempt count.
     * @param id Job id
     * @param owner This node
     * @param error What went wrong
     * @param backoff Delay before the next attempt
     * @param counts Whether this counts as a failed attempt
     * @return State the job is in now, empty when this node no longer holds it
     */
    public Optional<String> release(
        final long id, final String owner, final String error, final Duration backoff,
        final boolean counts
    ) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "UPDATE repo_jobs SET attempts = attempts + ?, last_error = ?, updated_at = NOW(),"
                    + " lease_owner = NULL, lease_until = NULL,"
                    + " not_before = NOW() + (? * INTERVAL '1 millisecond'),"
                    + " state = CASE WHEN attempts + ? >= ? THEN 'failed' ELSE 'pending' END"
                    + " WHERE id = ? AND lease_owner = ? AND state = 'running' RETURNING state"
            )) {
            final int inc = counts ? 1 : 0;
            stmt.setInt(1, inc);
            stmt.setString(2, error == null ? null : error.substring(0, Math.min(error.length(), 2000)));
            stmt.setLong(3, backoff.toMillis());
            stmt.setInt(4, inc);
            stmt.setInt(5, RepoJobDao.MAX_ATTEMPTS);
            stmt.setLong(6, id);
            stmt.setString(7, owner);
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to release repository job", ex);
        }
    }

    /**
     * Retry a failed job now.
     * @param id Job id
     * @return True when the job was failed and is queued again
     */
    public boolean retry(final long id) {
        return this.change(
            "UPDATE repo_jobs SET state = 'pending', attempts = 0, not_before = NOW(),"
                + " updated_at = NOW() WHERE id = ? AND state = 'failed'",
            id
        );
    }

    /**
     * End the restore window of a delete now.
     * @param id Purge job id
     * @return True when the job was still waiting
     */
    public boolean startNow(final long id) {
        final boolean res = this.change(
            "UPDATE repo_jobs SET not_before = NOW(), updated_at = NOW()"
                + " WHERE id = ? AND state = 'pending' AND phase = 'waiting'",
            id
        );
        if (res) {
            this.change(
                "UPDATE repository_tombstones SET purge_after = NOW() WHERE repo_key ="
                    + " (SELECT repo_key FROM repo_jobs WHERE id = ?)",
                id
            );
        }
        return res;
    }

    /**
     * A job.
     * @param id Job id
     * @return Job, empty when there is none
     */
    public Optional<RepoJob> get(final long id) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                String.format("SELECT %s FROM repo_jobs WHERE id = ?", RepoJobDao.COLUMNS)
            )) {
            stmt.setLong(1, id);
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next() ? Optional.of(RepoJobDao.job(rows)) : Optional.empty();
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to read repository job", ex);
        }
    }

    /**
     * Jobs, unfinished first, then newest first.
     * @param limit Maximum number of jobs
     * @return Jobs
     */
    public List<RepoJob> list(final int limit) {
        final List<RepoJob> res = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                String.format(
                    "SELECT %s FROM repo_jobs ORDER BY (finished_at IS NULL) DESC, id DESC LIMIT ?",
                    RepoJobDao.COLUMNS
                )
            )) {
            stmt.setInt(1, limit);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    res.add(RepoJobDao.job(rows));
                }
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to list repository jobs", ex);
        }
        return res;
    }

    /**
     * Number of jobs per state, for the gauge.
     * @param state Job state
     * @return Count
     */
    public long count(final String state) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM repo_jobs WHERE state = ?"
            )) {
            stmt.setString(1, state);
            try (ResultSet rows = stmt.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to count repository jobs", ex);
        }
    }

    /**
     * Drop finished jobs older than the given age.
     * @param age Age
     * @return Rows removed
     */
    public int prune(final Duration age) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                "DELETE FROM repo_jobs WHERE state IN ('done', 'cancelled')"
                    + " AND finished_at < NOW() - (? * INTERVAL '1 millisecond')"
            )) {
            stmt.setLong(1, age.toMillis());
            return stmt.executeUpdate();
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to prune repository jobs", ex);
        }
    }

    /**
     * Run a one-parameter update.
     * @param sql Statement
     * @param id Job id
     * @return True when a row changed
     */
    private boolean change(final String sql, final long id) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            return stmt.executeUpdate() > 0;
        } catch (final SQLException ex) {
            throw new IllegalStateException("Failed to update repository job", ex);
        }
    }

    /**
     * Map a job row.
     * @param rows Result set positioned on a row
     * @return Job
     * @throws SQLException On error
     */
    private static RepoJob job(final ResultSet rows) throws SQLException {
        final Timestamp finished = rows.getTimestamp("finished_at");
        return new RepoJob(
            rows.getLong("id"), RepoJob.Type.of(rows.getString("type")),
            rows.getString("repo_key"), rows.getString("repo_name"),
            rows.getString("state"), rows.getString("phase"),
            Json.createReader(new StringReader(rows.getString("params"))).readObject(),
            rows.getString("checkpoint"), rows.getLong("objects_done"),
            rows.getLong("rows_done"), rows.getInt("attempts"), rows.getString("last_error"),
            rows.getTimestamp("not_before").toInstant(), rows.getString("lease_owner"),
            rows.getString("created_by"), rows.getString("trace_id"),
            rows.getTimestamp("created_at").toInstant(),
            rows.getTimestamp("updated_at").toInstant(),
            finished == null ? null : finished.toInstant(),
            Duration.ofMillis(Math.max(0L, rows.getLong("age_ms")))
        );
    }
}
