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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cooldown.api.CooldownReason;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

public final class CooldownRepository {

    private final DataSource dataSource;

    public CooldownRepository(final DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    Optional<DbBlockRecord> find(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version
    ) {
        final String sql =
            "SELECT id, repo_type, repo_name, artifact, version, reason, status, blocked_by, "
                + "blocked_at, blocked_until, unblocked_at, unblocked_by, installed_by "
                + "FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? "
                + "AND artifact = ? AND version = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, artifact);
            stmt.setString(4, version);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readRecord(rs));
                }
                return Optional.empty();
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query cooldown record", err);
        }
    }

    DbBlockRecord insertBlock(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final CooldownReason reason,
        final Instant blockedAt,
        final Instant blockedUntil,
        final String blockedBy,
        final Optional<String> installedBy
    ) {
        final String sql =
            "INSERT INTO artifact_cooldowns(" +
                "repo_type, repo_name, artifact, version, reason, status, blocked_by, blocked_at, blocked_until, installed_by"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, artifact);
            stmt.setString(4, version);
            stmt.setString(5, reason.name());
            stmt.setString(6, BlockStatus.ACTIVE.name());
            stmt.setString(7, blockedBy);
            stmt.setLong(8, blockedAt.toEpochMilli());
            stmt.setLong(9, blockedUntil.toEpochMilli());
            if (installedBy.isPresent()) {
                stmt.setString(10, installedBy.get());
            } else {
                stmt.setNull(10, java.sql.Types.VARCHAR);
            }
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) {
                    final long id = keys.getLong(1);
                    return new DbBlockRecord(
                        id,
                        repoType,
                        repoName,
                        artifact,
                        version,
                        reason,
                        BlockStatus.ACTIVE,
                        blockedBy,
                        blockedAt,
                        blockedUntil,
                        Optional.empty(),
                        Optional.empty(),
                        installedBy
                    );
                }
                throw new IllegalStateException("No id returned for cooldown block");
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to insert cooldown block", err);
        }
    }

    /**
     * Archive all active blocks for a repository into
     * {@code artifact_cooldowns_history} and delete them from the live table
     * in a single CTE. Mirrors {@link #archiveExpiredBatch(int)} but keyed by
     * {@code (repo_type, repo_name)} and parameterised by archive reason +
     * actor. Used by the service-level bulk unblock ("Unblock All" per repo).
     * @param repoType Repository type.
     * @param repoName Repository name.
     * @param reason Archive reason written to each history row.
     * @param actor Username performing the action; written to {@code archived_by}.
     * @return Number of rows moved to history.
     */
    public int archiveAndDeleteByRepo(final String repoType, final String repoName,
                                      final ArchiveReason reason, final String actor) {
        final String sql = "WITH victims AS ("
            + "SELECT id FROM artifact_cooldowns"
            + " WHERE repo_type = ? AND repo_name = ? AND status = 'ACTIVE'"
            + " FOR UPDATE SKIP LOCKED"
            + "), archived AS ("
            + "INSERT INTO artifact_cooldowns_history ("
            + "original_id, repo_type, repo_name, artifact, version, "
            + "reason, blocked_by, blocked_at, blocked_until, "
            + "installed_by, archived_at, archive_reason, archived_by"
            + ") SELECT c.id, c.repo_type, c.repo_name, c.artifact, c.version, "
            + "c.reason, c.blocked_by, c.blocked_at, c.blocked_until, "
            + "c.installed_by, ?, ?, ? "
            + "FROM artifact_cooldowns c JOIN victims v ON v.id = c.id "
            + "RETURNING original_id) "
            + "DELETE FROM artifact_cooldowns c USING archived a "
            + "WHERE c.id = a.original_id";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setLong(3, Instant.now().toEpochMilli());
            stmt.setString(4, reason.name());
            stmt.setString(5, actor);
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Failed to archive+delete active blocks for repo", err);
        }
    }

    /**
     * Archive all active blocks for a given repo type into history and delete
     * them from the live table. Used when a repo-type cooldown override is
     * disabled via config.
     * @param repoType Repository type (e.g. {@code "maven-proxy"}).
     * @param reason Archive reason written to each history row.
     * @param actor Username performing the action.
     * @return Number of rows moved to history.
     */
    public int archiveAndDeleteByRepoType(final String repoType,
                                          final ArchiveReason reason, final String actor) {
        final String sql = "WITH victims AS ("
            + "SELECT id FROM artifact_cooldowns"
            + " WHERE repo_type = ? AND status = 'ACTIVE'"
            + " FOR UPDATE SKIP LOCKED"
            + "), archived AS ("
            + "INSERT INTO artifact_cooldowns_history ("
            + "original_id, repo_type, repo_name, artifact, version, "
            + "reason, blocked_by, blocked_at, blocked_until, "
            + "installed_by, archived_at, archive_reason, archived_by"
            + ") SELECT c.id, c.repo_type, c.repo_name, c.artifact, c.version, "
            + "c.reason, c.blocked_by, c.blocked_at, c.blocked_until, "
            + "c.installed_by, ?, ?, ? "
            + "FROM artifact_cooldowns c JOIN victims v ON v.id = c.id "
            + "RETURNING original_id) "
            + "DELETE FROM artifact_cooldowns c USING archived a "
            + "WHERE c.id = a.original_id";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setLong(2, Instant.now().toEpochMilli());
            stmt.setString(3, reason.name());
            stmt.setString(4, actor);
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Failed to archive+delete active blocks for repo type " + repoType, err);
        }
    }

    /**
     * Archive all active blocks globally into history and delete them from the
     * live table. Used when cooldown is disabled via config.
     * @param reason Archive reason written to each history row.
     * @param actor Username performing the action.
     * @return Number of rows moved to history.
     */
    public int archiveAndDeleteAll(final ArchiveReason reason, final String actor) {
        final String sql = "WITH victims AS ("
            + "SELECT id FROM artifact_cooldowns"
            + " WHERE status = 'ACTIVE'"
            + " FOR UPDATE SKIP LOCKED"
            + "), archived AS ("
            + "INSERT INTO artifact_cooldowns_history ("
            + "original_id, repo_type, repo_name, artifact, version, "
            + "reason, blocked_by, blocked_at, blocked_until, "
            + "installed_by, archived_at, archive_reason, archived_by"
            + ") SELECT c.id, c.repo_type, c.repo_name, c.artifact, c.version, "
            + "c.reason, c.blocked_by, c.blocked_at, c.blocked_until, "
            + "c.installed_by, ?, ?, ? "
            + "FROM artifact_cooldowns c JOIN victims v ON v.id = c.id "
            + "RETURNING original_id) "
            + "DELETE FROM artifact_cooldowns c USING archived a "
            + "WHERE c.id = a.original_id";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, Instant.now().toEpochMilli());
            stmt.setString(2, reason.name());
            stmt.setString(3, actor);
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Failed to archive+delete all active cooldown blocks", err);
        }
    }

    List<DbBlockRecord> findActiveForRepo(final String repoType, final String repoName) {
        final String sql =
            "SELECT id, repo_type, repo_name, artifact, version, reason, status, blocked_by, "
                + "blocked_at, blocked_until, unblocked_at, unblocked_by, installed_by "
                + "FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? AND status = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, BlockStatus.ACTIVE.name());
            try (ResultSet rs = stmt.executeQuery()) {
                final List<DbBlockRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readRecord(rs));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query active cooldowns", err);
        }
    }

    /**
     * Count active blocks for a repository.
     * Efficient query for metrics - only counts, doesn't load records.
     * @param repoType Repository type
     * @param repoName Repository name
     * @return Count of active blocks
     */
    public long countActiveBlocks(final String repoType, final String repoName) {
        final String sql =
            "SELECT COUNT(*) FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? AND status = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, BlockStatus.ACTIVE.name());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count active cooldowns", err);
        }
    }

    /**
     * Count all active blocks across all repositories.
     * Used for startup metrics initialization.
     * @return Map of repoType:repoName -> count
     */
    java.util.Map<String, Long> countAllActiveBlocks() {
        final String sql =
            "SELECT repo_type, repo_name, COUNT(*) as cnt FROM artifact_cooldowns "
                + "WHERE status = ? GROUP BY repo_type, repo_name";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, BlockStatus.ACTIVE.name());
            try (ResultSet rs = stmt.executeQuery()) {
                final java.util.Map<String, Long> result = new java.util.HashMap<>();
                while (rs.next()) {
                    final String key = rs.getString("repo_type") + ":" + rs.getString("repo_name");
                    result.put(key, rs.getLong("cnt"));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count all active cooldowns", err);
        }
    }

    /**
     * Count packages where ALL versions are blocked.
     * A package is "all blocked" if it has active blocks and no unblocked versions.
     * This is tracked via all_blocked status in the database.
     * @return Count of packages with all versions blocked
     */
    long countAllBlockedPackages() {
        final String sql =
            "SELECT COUNT(DISTINCT repo_type || ':' || repo_name || ':' || artifact) "
                + "FROM artifact_cooldowns WHERE status = 'ALL_BLOCKED'";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count all-blocked packages", err);
        }
    }

    /**
     * Mark a package as "all versions blocked".
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifact Artifact/package name
     * @return true if a new record was inserted, false if already marked
     */
    boolean markAllBlocked(final String repoType, final String repoName, final String artifact) {
        // First check if already marked
        final String checkSql =
            "SELECT id FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? "
                + "AND artifact = ? AND status = 'ALL_BLOCKED'";
        final String insertSql =
            "INSERT INTO artifact_cooldowns(repo_type, repo_name, artifact, version, reason, status, "
                + "blocked_by, blocked_at, blocked_until) VALUES (?, ?, ?, 'ALL', 'ALL_BLOCKED', 'ALL_BLOCKED', "
                + "'system', ?, ?)";
        try (Connection conn = this.dataSource.getConnection()) {
            // Check if already exists
            try (PreparedStatement check = conn.prepareStatement(checkSql)) {
                check.setString(1, repoType);
                check.setString(2, repoName);
                check.setString(3, artifact);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false; // Already marked
                    }
                }
            }
            // Insert marker
            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                insert.setString(1, repoType);
                insert.setString(2, repoName);
                insert.setString(3, artifact);
                final long now = Instant.now().toEpochMilli();
                insert.setLong(4, now);
                insert.setLong(5, Long.MAX_VALUE); // Never expires automatically
                insert.executeUpdate();
                return true; // New record inserted
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to mark package as all-blocked", err);
        }
    }

    /**
     * Unmark a package as "all versions blocked".
     * Called when a version is unblocked.
     * @param repoType Repository type
     * @param repoName Repository name
     * @param artifact Artifact/package name
     * @return true if a record was deleted (package was all-blocked)
     */
    boolean unmarkAllBlocked(final String repoType, final String repoName, final String artifact) {
        final String sql =
            "DELETE FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? "
                + "AND artifact = ? AND status = 'ALL_BLOCKED'";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, artifact);
            return stmt.executeUpdate() > 0;
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to unmark package as all-blocked", err);
        }
    }

    /**
     * Unmark all all-blocked packages for a repository.
     * @param repoType Repository type
     * @param repoName Repository name
     * @return Number of packages unmarked
     */
    int unmarkAllBlockedForRepo(final String repoType, final String repoName) {
        final String sql =
            "DELETE FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? "
                + "AND status = 'ALL_BLOCKED'";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to unmark all-blocked packages for repo", err);
        }
    }

    /**
     * Allowed sort columns (allowlist to prevent SQL injection).
     */
    private static final java.util.Set<String> SORTABLE_COLS = java.util.Set.of(
        "artifact", "version", "repo_name", "repo_type", "reason",
        "blocked_until", "blocked_at"
    );

    /**
     * Find all active blocks across all repos, paginated, with optional search and sort.
     * @param offset Row offset
     * @param limit Max rows
     * @param search Optional search term (filters artifact, repo_name, version)
     * @param sortBy Column to sort by (validated against SORTABLE_COLS)
     * @param sortAsc True for ascending, false for descending
     * @return List of active block records
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public List<DbBlockRecord> findAllActivePaginated(
        final int offset, final int limit, final String search,
        final String sortBy, final boolean sortAsc
    ) {
        final boolean hasSearch = search != null && !search.isBlank();
        final String col = SORTABLE_COLS.contains(sortBy) ? sortBy : "blocked_at";
        final String dir = sortAsc ? "ASC" : "DESC";
        final String orderBy = " ORDER BY " + col + " " + dir;
        final String base = "SELECT id, repo_type, repo_name, artifact, version, reason, status,"
            + " blocked_by, blocked_at, blocked_until, unblocked_at, unblocked_by, installed_by"
            + " FROM artifact_cooldowns WHERE status = ?";
        final String sql;
        if (hasSearch) {
            sql = base + " AND (artifact ILIKE ? OR repo_name ILIKE ? OR version ILIKE ?)"
                + orderBy + " LIMIT ? OFFSET ?";
        } else {
            sql = base + orderBy + " LIMIT ? OFFSET ?";
        }
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, BlockStatus.ACTIVE.name());
            if (hasSearch) {
                final String pattern = "%" + search.trim() + "%";
                stmt.setString(idx++, pattern);
                stmt.setString(idx++, pattern);
                stmt.setString(idx++, pattern);
            }
            stmt.setInt(idx++, limit);
            stmt.setInt(idx, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                final List<DbBlockRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readRecord(rs));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query active cooldowns", err);
        }
    }

    /**
     * Find all active blocks across all repos, paginated, with optional search.
     * Defaults to blocked_at DESC.
     * @param offset Row offset
     * @param limit Max rows
     * @param search Optional search term
     * @return List of active block records
     */
    public List<DbBlockRecord> findAllActivePaginated(
        final int offset, final int limit, final String search
    ) {
        return this.findAllActivePaginated(offset, limit, search, "blocked_at", false);
    }

    /**
     * Find all active blocks (no search filter).
     * @param offset Row offset
     * @param limit Max rows
     * @return List of active block records
     */
    public List<DbBlockRecord> findAllActivePaginated(final int offset, final int limit) {
        return this.findAllActivePaginated(offset, limit, null, "blocked_at", false);
    }

    /**
     * Count total active blocks across all repos, with optional search.
     * @param search Optional search term
     * @return Total count
     */
    public long countTotalActiveBlocks(final String search) {
        final boolean hasSearch = search != null && !search.isBlank();
        final String sql;
        if (hasSearch) {
            sql = "SELECT COUNT(*) FROM artifact_cooldowns WHERE status = ? "
                + "AND (artifact ILIKE ? OR repo_name ILIKE ? OR version ILIKE ?)";
        } else {
            sql = "SELECT COUNT(*) FROM artifact_cooldowns WHERE status = ?";
        }
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            int idx = 1;
            stmt.setString(idx++, BlockStatus.ACTIVE.name());
            if (hasSearch) {
                final String pattern = "%" + search.trim() + "%";
                stmt.setString(idx++, pattern);
                stmt.setString(idx++, pattern);
                stmt.setString(idx, pattern);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count active cooldowns", err);
        }
    }

    /**
     * Count total active blocks (no search filter).
     * @return Total count
     */
    public long countTotalActiveBlocks() {
        return this.countTotalActiveBlocks(null);
    }

    List<String> cachedVersions(
        final String repoType,
        final String repoName,
        final String artifact
    ) {
        final String sql = "SELECT version FROM artifacts WHERE repo_type = ? AND repo_name = ? AND name = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, artifact);
            try (ResultSet rs = stmt.executeQuery()) {
                final List<String> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query cached versions", err);
        }
    }

    // -----------------------------------------------------------------------
    // v2.2.0 cooldown global-rollout: archive + SQL-pushed permission queries.
    //
    // These methods coexist with the legacy methods above. The legacy methods
    // still use the status='ACTIVE' predicate against the artifact_cooldowns
    // table because the status column has not yet been dropped.
    // -----------------------------------------------------------------------

    /**
     * Allowed sort columns for findActivePaginated (allowlist to prevent SQL
     * injection). Keys are the API-facing names the UI sends; values are the
     * SQL column names interpolated into the ORDER BY clause.
     */
    private static final Map<String, String> ACTIVE_SORT_COL_MAP = Map.of(
        "artifact",      "artifact",
        "version",       "version",
        "repo_name",     "repo_name",
        "repo_type",     "repo_type",
        "reason",        "reason",
        "blocked_until", "blocked_until",
        "blocked_at",    "blocked_at"
    );

    /**
     * Allowed sort columns for findHistoryPaginated (allowlist).
     */
    private static final Map<String, String> HISTORY_SORT_COL_MAP = Map.of(
        "artifact",       "artifact",
        "version",        "version",
        "repo_name",      "repo_name",
        "repo_type",      "repo_type",
        "reason",         "reason",
        "archived_at",    "archived_at",
        "archive_reason", "archive_reason",
        "blocked_at",     "blocked_at",
        "blocked_until",  "blocked_until"
    );

    /**
     * SELECT list used by the legacy {@link #readRecord(ResultSet)} mapper.
     * Includes the {@code status} column because the column still exists in
     * v2.2.0 and the mapper expects it.
     */
    private static final String ACTIVE_SELECT_COLS =
        "SELECT id, repo_type, repo_name, artifact, version, reason, status, "
            + "blocked_by, blocked_at, blocked_until, unblocked_at, unblocked_by, "
            + "installed_by FROM artifact_cooldowns";

    private static final String HISTORY_SELECT_COLS =
        "SELECT id, original_id, repo_type, repo_name, artifact, version, reason, "
            + "blocked_by, blocked_at, blocked_until, installed_by, archived_at, "
            + "archive_reason, archived_by FROM artifact_cooldowns_history";

    /**
     * Archive a single live block row into history and delete it from the live
     * table. The INSERT and DELETE run in the same transaction — both succeed
     * or neither does. Idempotent on concurrent-delete races: if the row has
     * already been archived/deleted by another worker (e.g. the
     * {@code archiveExpiredBatch} cron), this method is a silent no-op instead
     * of throwing. This matches the semantics of the previous plain-DELETE
     * path and prevents spurious 500s when {@code expire()}/{@code release()}
     * races the cleanup cron on the same id.
     * @param blockId Id of the row in {@code artifact_cooldowns} to archive.
     * @param reason Reason the row is being archived.
     * @param actor Username performing the archive.
     * @throws IllegalStateException if the underlying transaction fails for
     *     reasons other than a missing row.
     */
    public void archiveAndDelete(final long blockId,
                                 final ArchiveReason reason,
                                 final String actor) {
        final String archiveSql = "INSERT INTO artifact_cooldowns_history ("
            + "original_id, repo_type, repo_name, artifact, version, "
            + "reason, blocked_by, blocked_at, blocked_until, "
            + "installed_by, archived_at, archive_reason, archived_by"
            + ") SELECT id, repo_type, repo_name, artifact, version, "
            + "reason, blocked_by, blocked_at, blocked_until, "
            + "installed_by, ?, ?, ? "
            + "FROM artifact_cooldowns WHERE id = ?";
        final String deleteSql = "DELETE FROM artifact_cooldowns WHERE id = ?";
        try (Connection conn = this.dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ins = conn.prepareStatement(archiveSql);
                PreparedStatement del = conn.prepareStatement(deleteSql)) {
                ins.setLong(1, Instant.now().toEpochMilli());
                ins.setString(2, reason.name());
                ins.setString(3, actor);
                ins.setLong(4, blockId);

                final int inserted = ins.executeUpdate();
                if (inserted == 0) {
                    // Row already archived/deleted by a concurrent cleanup
                    // (typically the archiveExpiredBatch cron). Idempotent
                    // no-op — commit the empty transaction and return.
                    conn.commit();
                    return;
                }

                del.setLong(1, blockId);
                final int deleted = del.executeUpdate();
                if (deleted == 0) {
                    // INSERT succeeded (1 row) but the row vanished between
                    // INSERT and DELETE — very unlikely under FOR UPDATE SKIP
                    // LOCKED semantics of the batch cleanup. Rollback so we
                    // don't leave an orphan history entry.
                    conn.rollback();
                    return;
                }
                conn.commit();
            } catch (final SQLException err) {
                conn.rollback();
                throw new IllegalStateException("archiveAndDelete failed", err);
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("archiveAndDelete failed", err);
        }
    }

    /**
     * Find active blocks restricted to a set of accessible repository names,
     * with optional filters and SQL-side pagination.
     * @param accessibleRepos Set of repo names the caller may see. Empty → [].
     * @param repoFilter Optional exact repo_name filter, may be null.
     * @param repoTypeFilter Optional exact repo_type filter, may be null.
     * @param search Optional substring to match against artifact/version/repo_name.
     * @param sortBy Column to sort by; validated via {@link #ACTIVE_SORT_COL_MAP}.
     * @param sortAsc Ascending (true) or descending (false).
     * @param offset Row offset.
     * @param limit Row limit.
     * @return List of matching active block records.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public List<DbBlockRecord> findActivePaginated(
        final Set<String> accessibleRepos,
        final String repoFilter,
        final String repoTypeFilter,
        final String search,
        final String sortBy,
        final boolean sortAsc,
        final int offset,
        final int limit
    ) {
        if (accessibleRepos == null || accessibleRepos.isEmpty()) {
            return Collections.emptyList();
        }
        final String col = sortBy == null
            ? "blocked_at" : ACTIVE_SORT_COL_MAP.getOrDefault(sortBy, "blocked_at");
        final String dir = sortAsc ? "ASC" : "DESC";
        final String sql = ACTIVE_SELECT_COLS
            + " WHERE repo_name = ANY(?)"
            + " AND (? IS NULL OR repo_name = ?)"
            + " AND (? IS NULL OR repo_type = ?)"
            + " AND (? IS NULL OR (artifact ILIKE '%' || ? || '%'"
            + " OR version ILIKE '%' || ? || '%'"
            + " OR repo_name ILIKE '%' || ? || '%'))"
            + " ORDER BY " + col + " " + dir
            + " LIMIT ? OFFSET ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            final Array reposArr = conn.createArrayOf(
                "varchar", accessibleRepos.toArray(String[]::new)
            );
            stmt.setArray(1, reposArr);
            bindOptionalFilters(stmt, 2, repoFilter, repoTypeFilter, search);
            stmt.setInt(10, limit);
            stmt.setInt(11, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                final List<DbBlockRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readRecord(rs));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query active cooldowns", err);
        }
    }

    /**
     * Count active blocks matching the same WHERE clause as
     * {@link #findActivePaginated}. Returns 0 when accessibleRepos is empty.
     * @param accessibleRepos Set of repo names the caller may see.
     * @param repoFilter Optional exact repo_name filter.
     * @param repoTypeFilter Optional exact repo_type filter.
     * @param search Optional substring filter.
     * @return Number of matching rows.
     */
    public long countActiveBlocks(
        final Set<String> accessibleRepos,
        final String repoFilter,
        final String repoTypeFilter,
        final String search
    ) {
        if (accessibleRepos == null || accessibleRepos.isEmpty()) {
            return 0L;
        }
        final String sql = "SELECT COUNT(*) FROM artifact_cooldowns"
            + " WHERE repo_name = ANY(?)"
            + " AND (? IS NULL OR repo_name = ?)"
            + " AND (? IS NULL OR repo_type = ?)"
            + " AND (? IS NULL OR (artifact ILIKE '%' || ? || '%'"
            + " OR version ILIKE '%' || ? || '%'"
            + " OR repo_name ILIKE '%' || ? || '%'))";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            final Array reposArr = conn.createArrayOf(
                "varchar", accessibleRepos.toArray(String[]::new)
            );
            stmt.setArray(1, reposArr);
            bindOptionalFilters(stmt, 2, repoFilter, repoTypeFilter, search);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count active cooldowns", err);
        }
    }

    /**
     * Archive a batch of expired blocks into history and delete them from the
     * live table. Mirrors the V121 cron SQL — keeps the {@code status='ACTIVE'}
     * predicate because the column still exists.
     * @param limit Maximum number of rows to archive in this batch.
     * @return Number of rows moved.
     */
    public int archiveExpiredBatch(final int limit) {
        final String sql = "WITH victims AS ("
            + "SELECT id FROM artifact_cooldowns"
            + " WHERE status = 'ACTIVE' AND blocked_until < ?"
            + " ORDER BY blocked_until"
            + " LIMIT ? FOR UPDATE SKIP LOCKED"
            + "), archived AS ("
            + "INSERT INTO artifact_cooldowns_history ("
            + "original_id, repo_type, repo_name, artifact, version, "
            + "reason, blocked_by, blocked_at, blocked_until, "
            + "installed_by, archived_at, archive_reason, archived_by"
            + ") SELECT c.id, c.repo_type, c.repo_name, c.artifact, c.version, "
            + "c.reason, c.blocked_by, c.blocked_at, c.blocked_until, "
            + "c.installed_by, ?, 'EXPIRED', 'system' "
            + "FROM artifact_cooldowns c JOIN victims v ON v.id = c.id "
            + "RETURNING original_id) "
            + "DELETE FROM artifact_cooldowns c USING archived a "
            + "WHERE c.id = a.original_id";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            final long now = Instant.now().toEpochMilli();
            stmt.setLong(1, now);
            stmt.setInt(2, limit);
            stmt.setLong(3, now);
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to archive expired batch", err);
        }
    }

    /**
     * Purge rows from history whose {@code archived_at} is older than the
     * given cutoff (epoch millis), up to the given limit. Mirrors the V121
     * {@code purge-cooldown-history} cron.
     * @param cutoffMillis Epoch-ms threshold — archived_at strictly less is purged.
     * @param limit Maximum rows to delete.
     * @return Number of rows deleted.
     */
    public int purgeHistoryOlderThan(final long cutoffMillis, final int limit) {
        final String sql = "WITH victims AS ("
            + "SELECT id FROM artifact_cooldowns_history"
            + " WHERE archived_at < ?"
            + " ORDER BY archived_at LIMIT ?) "
            + "DELETE FROM artifact_cooldowns_history h USING victims v "
            + "WHERE h.id = v.id";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, cutoffMillis);
            stmt.setInt(2, limit);
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to purge history", err);
        }
    }

    /**
     * Find history rows restricted to accessible repos with optional filters
     * and SQL-side pagination.
     * @param accessibleRepos Set of repo names the caller may see. Empty → [].
     * @param repoFilter Optional exact repo_name filter.
     * @param repoTypeFilter Optional exact repo_type filter.
     * @param search Optional substring filter against artifact/version/repo_name.
     * @param sortBy Column to sort by; validated via {@link #HISTORY_SORT_COL_MAP}.
     * @param sortAsc Ascending (true) or descending (false).
     * @param offset Row offset.
     * @param limit Row limit.
     * @return List of matching history records.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public List<DbHistoryRecord> findHistoryPaginated(
        final Set<String> accessibleRepos,
        final String repoFilter,
        final String repoTypeFilter,
        final String search,
        final String sortBy,
        final boolean sortAsc,
        final int offset,
        final int limit
    ) {
        if (accessibleRepos == null || accessibleRepos.isEmpty()) {
            return Collections.emptyList();
        }
        final String col = sortBy == null
            ? "archived_at" : HISTORY_SORT_COL_MAP.getOrDefault(sortBy, "archived_at");
        final String dir = sortAsc ? "ASC" : "DESC";
        final String sql = HISTORY_SELECT_COLS
            + " WHERE repo_name = ANY(?)"
            + " AND (? IS NULL OR repo_name = ?)"
            + " AND (? IS NULL OR repo_type = ?)"
            + " AND (? IS NULL OR (artifact ILIKE '%' || ? || '%'"
            + " OR version ILIKE '%' || ? || '%'"
            + " OR repo_name ILIKE '%' || ? || '%'))"
            + " ORDER BY " + col + " " + dir
            + " LIMIT ? OFFSET ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            final Array reposArr = conn.createArrayOf(
                "varchar", accessibleRepos.toArray(String[]::new)
            );
            stmt.setArray(1, reposArr);
            bindOptionalFilters(stmt, 2, repoFilter, repoTypeFilter, search);
            stmt.setInt(10, limit);
            stmt.setInt(11, offset);
            try (ResultSet rs = stmt.executeQuery()) {
                final List<DbHistoryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapHistoryRow(rs));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query cooldown history", err);
        }
    }

    /**
     * Count history rows matching the same WHERE clause as
     * {@link #findHistoryPaginated}. Returns 0 when accessibleRepos is empty.
     * @param accessibleRepos Set of repo names the caller may see.
     * @param repoFilter Optional exact repo_name filter.
     * @param repoTypeFilter Optional exact repo_type filter.
     * @param search Optional substring filter.
     * @return Number of matching rows.
     */
    public long countHistory(
        final Set<String> accessibleRepos,
        final String repoFilter,
        final String repoTypeFilter,
        final String search
    ) {
        if (accessibleRepos == null || accessibleRepos.isEmpty()) {
            return 0L;
        }
        final String sql = "SELECT COUNT(*) FROM artifact_cooldowns_history"
            + " WHERE repo_name = ANY(?)"
            + " AND (? IS NULL OR repo_name = ?)"
            + " AND (? IS NULL OR repo_type = ?)"
            + " AND (? IS NULL OR (artifact ILIKE '%' || ? || '%'"
            + " OR version ILIKE '%' || ? || '%'"
            + " OR repo_name ILIKE '%' || ? || '%'))";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            final Array reposArr = conn.createArrayOf(
                "varchar", accessibleRepos.toArray(String[]::new)
            );
            stmt.setArray(1, reposArr);
            bindOptionalFilters(stmt, 2, repoFilter, repoTypeFilter, search);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to count cooldown history", err);
        }
    }

    /**
     * Bind the three optional filter triples used by findActivePaginated /
     * findHistoryPaginated / the count variants. Binds parameters starting at
     * {@code startIdx} and consumes 8 positions: repoFilter × 2, repoType × 2,
     * search × 4 (one for the IS NULL guard, three for the ILIKE clauses).
     * @param stmt PreparedStatement.
     * @param startIdx Starting 1-based parameter index.
     * @param repoFilter Optional repo_name filter.
     * @param repoTypeFilter Optional repo_type filter.
     * @param search Optional substring filter.
     * @throws SQLException on bind failure.
     */
    private static void bindOptionalFilters(
        final PreparedStatement stmt,
        final int startIdx,
        final String repoFilter,
        final String repoTypeFilter,
        final String search
    ) throws SQLException {
        int idx = startIdx;
        setNullableString(stmt, idx++, repoFilter);
        setNullableString(stmt, idx++, repoFilter);
        setNullableString(stmt, idx++, repoTypeFilter);
        setNullableString(stmt, idx++, repoTypeFilter);
        final String searchValue = (search == null || search.isBlank()) ? null : search.trim();
        setNullableString(stmt, idx++, searchValue);
        setNullableString(stmt, idx++, searchValue);
        setNullableString(stmt, idx++, searchValue);
        setNullableString(stmt, idx, searchValue);
    }

    private static void setNullableString(
        final PreparedStatement stmt, final int idx, final String value
    ) throws SQLException {
        if (value == null) {
            stmt.setNull(idx, java.sql.Types.VARCHAR);
        } else {
            stmt.setString(idx, value);
        }
    }

    private static DbHistoryRecord mapHistoryRow(final ResultSet rs) throws SQLException {
        final String installedBy = rs.getString("installed_by");
        return new DbHistoryRecord(
            rs.getLong("id"),
            rs.getLong("original_id"),
            rs.getString("repo_type"),
            rs.getString("repo_name"),
            rs.getString("artifact"),
            rs.getString("version"),
            CooldownReason.valueOf(rs.getString("reason")),
            rs.getString("blocked_by"),
            Instant.ofEpochMilli(rs.getLong("blocked_at")),
            Instant.ofEpochMilli(rs.getLong("blocked_until")),
            installedBy == null ? Optional.empty() : Optional.of(installedBy),
            Instant.ofEpochMilli(rs.getLong("archived_at")),
            ArchiveReason.valueOf(rs.getString("archive_reason")),
            rs.getString("archived_by")
        );
    }

    private static DbBlockRecord readRecord(final ResultSet rs) throws SQLException {
        final long id = rs.getLong("id");
        final String repoType = rs.getString("repo_type");
        final String repoName = rs.getString("repo_name");
        final String artifact = rs.getString("artifact");
        final String version = rs.getString("version");
        final CooldownReason reason = CooldownReason.valueOf(rs.getString("reason"));
        final BlockStatus status = BlockStatus.fromDatabase(rs.getString("status"));
        final String blockedBy = rs.getString("blocked_by");
        final Instant blockedAt = Instant.ofEpochMilli(rs.getLong("blocked_at"));
        final Instant blockedUntil = Instant.ofEpochMilli(rs.getLong("blocked_until"));
        final long unblockedAtRaw = rs.getLong("unblocked_at");
        final Optional<Instant> unblockedAt = rs.wasNull()
            ? Optional.empty()
            : Optional.of(Instant.ofEpochMilli(unblockedAtRaw));
        final String unblockedByRaw = rs.getString("unblocked_by");
        final Optional<String> unblockedBy = rs.wasNull()
            ? Optional.empty()
            : Optional.of(unblockedByRaw);
        final String installedByRaw = rs.getString("installed_by");
        final Optional<String> installedBy = rs.wasNull()
            ? Optional.empty()
            : Optional.of(installedByRaw);
        return new DbBlockRecord(
            id,
            repoType,
            repoName,
            artifact,
            version,
            reason,
            status,
            blockedBy,
            blockedAt,
            blockedUntil,
            unblockedAt,
            unblockedBy,
            installedBy
        );
    }
}
