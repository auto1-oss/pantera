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
package com.auto1.pantera.importer;

import com.auto1.pantera.importer.api.ChecksumPolicy;
import com.auto1.pantera.importer.api.DigestType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * PostgreSQL persistence for importer sessions.
 *
 * @since 1.0
 */
public final class ImportSessionStore {

    /**
     * JDBC data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     *
     * @param source Data source
     */
    public ImportSessionStore(final DataSource source) {
        this.source = source;
    }

    /**
     * Start or resume session for request.
     *
     * @param request Import request metadata
     * @return Session record
     */
    ImportSession start(final ImportRequest request) {
        try (Connection conn = this.source.getConnection()) {
            conn.setAutoCommit(false);
            final long now = System.currentTimeMillis();
            insertIfAbsent(conn, request, now);
            final ImportSession existing = selectForUpdate(conn, request.idempotency());
            final ImportSession session;
            if (existing.status() == ImportSessionStatus.IN_PROGRESS) {
                // Only update metadata and bump attempt when actively in progress.
                session = updateMetadata(conn, existing, request, now);
            } else {
                // Preserve terminal states (COMPLETED, SKIPPED, QUARANTINED, FAILED)
                session = existing;
            }
            conn.commit();
            return session;
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to start import session", err);
        }
    }

    /**
     * Mark session as completed.
     *
     * @param session Session
     * @param size Uploaded size
     * @param digests Digests (optional)
     */
    void markCompleted(
        final ImportSession session,
        final long size,
        final Map<DigestType, String> digests
    ) {
        updateTerminal(session, ImportSessionStatus.COMPLETED, size, digests, null, null);
    }

    /**
     * Mark session as quarantined.
     *
     * @param session Session
     * @param size Uploaded size
     * @param digests Digests
     * @param reason Reason
     * @param quarantineKey Storage key where file was quarantined
     */
    void markQuarantined(
        final ImportSession session,
        final long size,
        final Map<DigestType, String> digests,
        final String reason,
        final String quarantineKey
    ) {
        updateTerminal(session, ImportSessionStatus.QUARANTINED, size, digests, reason, quarantineKey);
    }

    /**
     * Mark session as failed.
     *
     * @param session Session
     * @param reason Failure reason
     */
    void markFailed(final ImportSession session, final String reason) {
        updateTerminal(session, ImportSessionStatus.FAILED, session.size().orElse(0L), null, reason, null);
    }

    /**
     * Mark session as skipped (artifact already present).
     *
     * @param session Session
     */
    void markSkipped(final ImportSession session) {
        updateTerminal(session, ImportSessionStatus.SKIPPED, session.size().orElse(0L), null, null, null);
    }

    /**
     * Insert record if absent.
     *
     * @param conn Connection
     * @param request Request
     * @param now Timestamp
     * @throws SQLException On error
     */
    private static void insertIfAbsent(
        final Connection conn,
        final ImportRequest request,
        final long now
    ) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            String.join(
                " ",
                "INSERT INTO import_sessions(",
                " idempotency_key, repo_name, repo_type, artifact_path, artifact_name, artifact_version,",
                " size_bytes, checksum_sha1, checksum_sha256, checksum_md5, checksum_policy, status,",
                " attempt_count, created_at, updated_at",
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,1,?,?) ON CONFLICT (idempotency_key) DO NOTHING"
            )
        )) {
            int idx = 1;
            stmt.setString(idx++, request.idempotency());
            stmt.setString(idx++, request.repo());
            stmt.setString(idx++, request.repoType());
            stmt.setString(idx++, request.path());
            stmt.setString(idx++, request.artifact().orElse(null));
            stmt.setString(idx++, request.version().orElse(null));
            if (request.size().isPresent()) {
                stmt.setLong(idx++, request.size().get());
            } else {
                stmt.setNull(idx++, java.sql.Types.BIGINT);
            }
            stmt.setString(idx++, request.sha1().orElse(null));
            stmt.setString(idx++, request.sha256().orElse(null));
            stmt.setString(idx++, request.md5().orElse(null));
            stmt.setString(idx++, request.policy().name());
            stmt.setString(idx++, ImportSessionStatus.IN_PROGRESS.name());
            stmt.setTimestamp(idx++, new Timestamp(now));
            stmt.setTimestamp(idx, new Timestamp(now));
            stmt.executeUpdate();
        }
    }

    /**
     * Select and lock session.
     *
     * @param conn Connection
     * @param key Idempotency key
     * @return Session
     * @throws SQLException On error
     */
    private static ImportSession selectForUpdate(final Connection conn, final String key)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            String.join(
                " ",
                "SELECT id, idempotency_key, status, repo_name, repo_type, artifact_path, artifact_name,",
                " artifact_version, size_bytes, checksum_sha1, checksum_sha256, checksum_md5,",
                " checksum_policy, attempt_count",
                " FROM import_sessions WHERE idempotency_key = ? FOR UPDATE"
            )
        )) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Import session not found for key " + key);
                }
                return map(rs);
            }
        }
    }

    /**
     * Update metadata for new attempt.
     *
     * @param conn Connection
     * @param existing Existing session
     * @param request Request
     * @param now Timestamp
     * @return Updated session
     * @throws SQLException On error
     */
    private static ImportSession updateMetadata(
        final Connection conn,
        final ImportSession existing,
        final ImportRequest request,
        final long now
    ) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            String.join(
                " ",
                "UPDATE import_sessions",
                " SET repo_name = ?, repo_type = ?, artifact_path = ?,",
                " artifact_name = ?, artifact_version = ?, size_bytes = ?,",
                " checksum_sha1 = ?, checksum_sha256 = ?, checksum_md5 = ?,",
                " checksum_policy = ?, status = ?, attempt_count = attempt_count + 1,",
                " updated_at = ?",
                " WHERE id = ?"
            )
        )) {
            int idx = 1;
            stmt.setString(idx++, request.repo());
            stmt.setString(idx++, request.repoType());
            stmt.setString(idx++, request.path());
            stmt.setString(idx++, request.artifact().orElse(null));
            stmt.setString(idx++, request.version().orElse(null));
            if (request.size().isPresent()) {
                stmt.setLong(idx++, request.size().get());
            } else {
                stmt.setNull(idx++, java.sql.Types.BIGINT);
            }
            stmt.setString(idx++, request.sha1().orElse(null));
            stmt.setString(idx++, request.sha256().orElse(null));
            stmt.setString(idx++, request.md5().orElse(null));
            stmt.setString(idx++, request.policy().name());
            stmt.setString(idx++, ImportSessionStatus.IN_PROGRESS.name());
            stmt.setTimestamp(idx++, new Timestamp(now));
            stmt.setLong(idx, existing.id());
            stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT id, idempotency_key, status, repo_name, repo_type, artifact_path, artifact_name," +
                " artifact_version, size_bytes, checksum_sha1, checksum_sha256, checksum_md5," +
                " checksum_policy, attempt_count FROM import_sessions WHERE id = ?"
        )) {
            stmt.setLong(1, existing.id());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("Session missing after metadata update");
                }
                return map(rs);
            }
        }
    }

    /**
     * Update terminal status.
     *
     * @param session Session
     * @param status Status
     * @param size Size
     * @param digests Digests
     * @param reason Reason
     * @param quarantine Quarantine key
     */
    private void updateTerminal(
        final ImportSession session,
        final ImportSessionStatus status,
        final long size,
        final Map<DigestType, String> digests,
        final String reason,
        final String quarantine
    ) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement stmt = conn.prepareStatement(
                String.join(
                    " ",
                    "UPDATE import_sessions",
                    " SET status = ?, completed_at = ?, updated_at = ?, size_bytes = ?,",
                    " checksum_sha1 = COALESCE(?, checksum_sha1),",
                    " checksum_sha256 = COALESCE(?, checksum_sha256),",
                    " checksum_md5 = COALESCE(?, checksum_md5),",
                    " last_error = ?, quarantine_path = ?",
                    " WHERE id = ?"
                )
            )) {
            final long now = System.currentTimeMillis();
            int idx = 1;
            stmt.setString(idx++, status.name());
            stmt.setTimestamp(idx++, new Timestamp(now));
            stmt.setTimestamp(idx++, new Timestamp(now));
            stmt.setLong(idx++, size);
            stmt.setString(idx++, digestValue(digests, DigestType.SHA1).orElse(null));
            stmt.setString(idx++, digestValue(digests, DigestType.SHA256).orElse(null));
            stmt.setString(idx++, digestValue(digests, DigestType.MD5).orElse(null));
            stmt.setString(idx++, reason);
            stmt.setString(idx++, quarantine);
            stmt.setLong(idx, session.id());
            stmt.executeUpdate();
        } catch (final SQLException err) {
            throw new IllegalStateException("Failed to update import session", err);
        }
    }

    /**
     * Map result set row to {@link ImportSession}.
     *
     * @param rs Result set
     * @return Session
     * @throws SQLException On error
     */
    private static ImportSession map(final ResultSet rs) throws SQLException {
        return new ImportSession(
            rs.getLong("id"),
            rs.getString("idempotency_key") == null
                ? "" : rs.getString("idempotency_key"),
            ImportSessionStatus.valueOf(rs.getString("status")),
            rs.getString("repo_name"),
            rs.getString("repo_type"),
            rs.getString("artifact_path"),
            rs.getString("artifact_name"),
            rs.getString("artifact_version"),
            rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes"),
            rs.getString("checksum_sha1"),
            rs.getString("checksum_sha256"),
            rs.getString("checksum_md5"),
            ChecksumPolicy.valueOf(rs.getString("checksum_policy")),
            rs.getInt("attempt_count")
        );
    }

    /**
     * Extract digest value if available.
     *
     * @param digests Digests map
     * @param type Digest type
     * @return Optional value
     */
    private static Optional<String> digestValue(
        final Map<DigestType, String> digests,
        final DigestType type
    ) {
        if (digests == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(digests.get(type));
    }
}
