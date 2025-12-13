/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;

import com.artipie.cooldown.CooldownReason;
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

final class CooldownRepository {

    private final DataSource dataSource;

    CooldownRepository(final DataSource dataSource) {
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

    void updateStatus(
        final long blockId,
        final BlockStatus status,
        final Optional<Instant> unblockedAt,
        final Optional<String> unblockedBy
    ) {
        final String sql =
            "UPDATE artifact_cooldowns SET status = ?, unblocked_at = ?, unblocked_by = ? WHERE id = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            if (unblockedAt.isPresent()) {
                stmt.setLong(2, unblockedAt.get().toEpochMilli());
            } else {
                stmt.setNull(2, java.sql.Types.BIGINT);
            }
            if (unblockedBy.isPresent()) {
                stmt.setString(3, unblockedBy.get());
            } else {
                stmt.setNull(3, java.sql.Types.VARCHAR);
            }
            stmt.setLong(4, blockId);
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to update cooldown status", err);
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
    long countActiveBlocks(final String repoType, final String repoName) {
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
