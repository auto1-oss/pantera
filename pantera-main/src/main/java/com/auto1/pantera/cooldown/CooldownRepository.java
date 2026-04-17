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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
     * Delete a cooldown block record by id.
     * Callers must log the record details before calling this method.
     * @param blockId Record id to delete
     */
    void deleteBlock(final long blockId) {
        final String sql = "DELETE FROM artifact_cooldowns WHERE id = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, blockId);
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to delete cooldown block", err);
        }
    }

    /**
     * Delete all active blocks for a repository in a single statement.
     * @param repoType Repository type
     * @param repoName Repository name
     * @return Number of deleted rows
     */
    int deleteActiveBlocksForRepo(final String repoType, final String repoName) {
        final String sql =
            "DELETE FROM artifact_cooldowns WHERE repo_type = ? AND repo_name = ? AND status = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, repoName);
            stmt.setString(3, BlockStatus.ACTIVE.name());
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to delete active blocks for repo", err);
        }
    }

    /**
     * Delete all active blocks globally. Used when cooldown is disabled.
     * @param actor Username performing the action
     * @return Number of deleted rows
     */
    public int unblockAll(final String actor) {
        final String sql = "DELETE FROM artifact_cooldowns WHERE status = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, BlockStatus.ACTIVE.name());
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to unblock all cooldown blocks", err);
        }
    }

    /**
     * Delete all active blocks for a specific repo type. Used when a repo type
     * cooldown override is disabled.
     * @param repoType Repository type (e.g. "maven-proxy")
     * @param actor Username performing the action
     * @return Number of deleted rows
     */
    public int unblockByRepoType(final String repoType, final String actor) {
        final String sql =
            "DELETE FROM artifact_cooldowns WHERE repo_type = ? AND status = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, repoType);
            stmt.setString(2, BlockStatus.ACTIVE.name());
            return stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException(
                "Failed to unblock blocks for repo type " + repoType, err);
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
