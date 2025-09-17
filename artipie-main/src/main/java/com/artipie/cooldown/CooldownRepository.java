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
                + "blocked_at, blocked_until, unblocked_at, unblocked_by, parent_block_id "
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
        final Optional<Long> parentId
    ) {
        final String sql =
            "INSERT INTO artifact_cooldowns(" +
                "repo_type, repo_name, artifact, version, reason, status, blocked_by, blocked_at, blocked_until, parent_block_id"
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
            if (parentId.isPresent()) {
                stmt.setLong(10, parentId.get());
            } else {
                stmt.setNull(10, java.sql.Types.BIGINT);
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
                        parentId
                    );
                }
                throw new IllegalStateException("No id returned for cooldown block");
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to insert cooldown block", err);
        }
    }

    void insertDependencies(
        final String repoType,
        final String repoName,
        final List<CooldownDependency> dependencies,
        final CooldownReason reason,
        final Instant blockedAt,
        final Instant blockedUntil,
        final String blockedBy,
        final long parentId
    ) {
        if (dependencies.isEmpty()) {
            return;
        }
        final String sql =
            "INSERT INTO artifact_cooldowns(" +
                "repo_type, repo_name, artifact, version, reason, status, blocked_by, blocked_at, blocked_until, parent_block_id"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (repo_name, artifact, version) DO NOTHING";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (CooldownDependency dependency : dependencies) {
                stmt.setString(1, repoType);
                stmt.setString(2, repoName);
                stmt.setString(3, dependency.artifact());
                stmt.setString(4, dependency.version());
                stmt.setString(5, reason.name());
                stmt.setString(6, BlockStatus.ACTIVE.name());
                stmt.setString(7, blockedBy);
                stmt.setLong(8, blockedAt.toEpochMilli());
                stmt.setLong(9, blockedUntil.toEpochMilli());
                stmt.setLong(10, parentId);
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to insert dependency blocks", err);
        }
    }

    void recordAttempt(final long blockId, final String requestedBy, final Instant attemptedAt) {
        final String sql =
            "INSERT INTO artifact_cooldown_attempts(block_id, requested_by, attempted_at) VALUES (?,?,?)";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, blockId);
            stmt.setString(2, requestedBy);
            stmt.setLong(3, attemptedAt.toEpochMilli());
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to record cooldown attempt", err);
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

    List<DbBlockRecord> dependenciesOf(final long parentId) {
        final String sql =
            "SELECT id, repo_type, repo_name, artifact, version, reason, status, blocked_by, "
                + "blocked_at, blocked_until, unblocked_at, unblocked_by, parent_block_id "
                + "FROM artifact_cooldowns WHERE parent_block_id = ?";
        try (Connection conn = this.dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, parentId);
            try (ResultSet rs = stmt.executeQuery()) {
                final List<DbBlockRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(readRecord(rs));
                }
                return result;
            }
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to query dependencies", err);
        }
    }

    List<DbBlockRecord> findActiveForRepo(final String repoType, final String repoName) {
        final String sql =
            "SELECT id, repo_type, repo_name, artifact, version, reason, status, blocked_by, "
                + "blocked_at, blocked_until, unblocked_at, unblocked_by, parent_block_id "
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
        final long parentRaw = rs.getLong("parent_block_id");
        final Optional<Long> parent = rs.wasNull() ? Optional.empty() : Optional.of(parentRaw);
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
            parent
        );
    }
}
