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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * DAO for the {@code pgp_keyring} table (V131) — admin-uploaded trusted PGP
 * public keys consulted by {@code PgpVerifier} when a repo's
 * {@code verifyPgp} flag is enabled (WS4-maven.1-.3).
 * @since 2.3.0
 */
public final class PgpKeyringDao {

    /**
     * Database data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database data source
     */
    public PgpKeyringDao(final DataSource source) {
        this.source = source;
    }

    /**
     * List every registered key (newest first), without the armored key
     * material — the admin UI only needs identity/provenance fields.
     * @return Registered keys
     */
    public List<KeyRow> list() {
        final String sql = String.join(" ",
            "SELECT key_id_hex, fingerprint, uploaded_by, uploaded_at, description",
            "FROM pgp_keyring ORDER BY uploaded_at DESC"
        );
        final List<KeyRow> rows = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new KeyRow(
                    rs.getString("key_id_hex"),
                    rs.getString("fingerprint"),
                    rs.getString("uploaded_by"),
                    rs.getTimestamp("uploaded_at").toInstant(),
                    rs.getString("description")
                ));
            }
        } catch (final java.sql.SQLException ex) {
            throw new IllegalStateException("Failed to list pgp_keyring", ex);
        }
        return rows;
    }

    /**
     * Insert a key. A pre-existing {@code key_id_hex} is left untouched
     * (the admin re-uploads the same public block; nothing to update).
     * @param keyIdHex 16-char uppercase hex long key id
     * @param fingerprint 40-char uppercase hex SHA-1 fingerprint
     * @param armored Full ASCII-armored public key block
     * @param uploadedBy Admin username
     * @param description Optional free-text note, may be {@code null}
     * @return True when a new row was inserted, false when the key id
     *         already existed
     */
    public boolean insert(
        final String keyIdHex, final String fingerprint, final String armored,
        final String uploadedBy, final String description
    ) {
        final String sql = String.join(" ",
            "INSERT INTO pgp_keyring (key_id_hex, fingerprint, public_key_armored,",
            "uploaded_by, description) VALUES (?, ?, ?, ?, ?)",
            "ON CONFLICT (key_id_hex) DO NOTHING"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyIdHex);
            ps.setString(2, fingerprint);
            ps.setString(3, armored);
            ps.setString(4, uploadedBy);
            ps.setString(5, description);
            return ps.executeUpdate() > 0;
        } catch (final java.sql.SQLException ex) {
            throw new IllegalStateException("Failed to insert pgp_keyring row: " + keyIdHex, ex);
        }
    }

    /**
     * Delete a key by its long key id.
     * @param keyIdHex 16-char uppercase hex long key id
     * @return True when a row was deleted
     */
    public boolean delete(final String keyIdHex) {
        final String sql = "DELETE FROM pgp_keyring WHERE key_id_hex = ?";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyIdHex);
            return ps.executeUpdate() > 0;
        } catch (final java.sql.SQLException ex) {
            throw new IllegalStateException("Failed to delete pgp_keyring row: " + keyIdHex, ex);
        }
    }

    /**
     * A single {@code pgp_keyring} row, without the armored key material.
     * @param keyIdHex 16-char uppercase hex long key id
     * @param fingerprint 40-char uppercase hex SHA-1 fingerprint
     * @param uploadedBy Admin username who uploaded the key
     * @param uploadedAt Upload timestamp
     * @param description Optional free-text note, may be {@code null}
     */
    public record KeyRow(
        String keyIdHex, String fingerprint, String uploadedBy,
        Instant uploadedAt, String description
    ) {
    }
}
