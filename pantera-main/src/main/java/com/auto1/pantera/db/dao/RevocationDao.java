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
package com.auto1.pantera.db.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * DAO for the revocation_blocklist table.
 * Stores JWT revocation entries (by JTI or username) with an expiry.
 * @since 2.1.0
 */
public final class RevocationDao {

    /**
     * Database data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database data source
     */
    public RevocationDao(final DataSource source) {
        this.source = source;
    }

    /**
     * Insert a revocation entry with a TTL.
     * @param entryType Entry type: "jti" or "username"
     * @param entryValue The JTI string or username
     * @param ttlSeconds Time-to-live in seconds; expires_at = NOW() + ttl
     */
    public void insert(final String entryType, final String entryValue, final int ttlSeconds) {
        final String sql = String.join(" ",
            "INSERT INTO revocation_blocklist (entry_type, entry_value, expires_at)",
            "VALUES (?, ?, NOW() + (? || ' seconds')::INTERVAL)"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryType);
            ps.setString(2, entryValue);
            ps.setInt(3, ttlSeconds);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to insert revocation entry", ex);
        }
    }

    /**
     * Check if an active (non-expired) revocation entry exists.
     * @param entryType Entry type: "jti" or "username"
     * @param entryValue The JTI string or username
     * @return True if a non-expired entry exists
     */
    public boolean isRevoked(final String entryType, final String entryValue) {
        final String sql = String.join(" ",
            "SELECT 1 FROM revocation_blocklist",
            "WHERE entry_type = ? AND entry_value = ? AND expires_at > NOW()",
            "LIMIT 1"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entryType);
            ps.setString(2, entryValue);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to check revocation status", ex);
        }
    }

    /**
     * Poll for revocation entries created since a given timestamp that have not yet expired.
     * Used by DbRevocationBlocklist to refresh local cache.
     * @param since Fetch entries created after this instant
     * @return List of active revocation entries created since the given timestamp
     */
    public List<RevocationEntry> pollSince(final Instant since) {
        final String sql = String.join(" ",
            "SELECT entry_type, entry_value, expires_at",
            "FROM revocation_blocklist",
            "WHERE created_at > ? AND expires_at > NOW()",
            "ORDER BY created_at ASC"
        );
        final List<RevocationEntry> entries = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(since));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new RevocationEntry(
                        rs.getString("entry_type"),
                        rs.getString("entry_value"),
                        rs.getTimestamp("expires_at").toInstant()
                    ));
                }
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to poll revocation entries", ex);
        }
        return entries;
    }

    /**
     * A single revocation blocklist entry.
     */
    public record RevocationEntry(String entryType, String entryValue, Instant expiresAt) {
    }
}
