/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * DAO for user API tokens (user_tokens table).
 * @since 1.21.0
 */
public final class UserTokenDao {

    /**
     * Database data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database data source
     */
    public UserTokenDao(final DataSource source) {
        this.source = source;
    }

    /**
     * Store a newly issued token.
     * @param id Token UUID (same as jti claim)
     * @param username Username
     * @param label Human-readable label
     * @param tokenValue Raw JWT string (hashed before storage)
     * @param expiresAt Expiry timestamp, null for permanent
     */
    public void store(final UUID id, final String username, final String label,
        final String tokenValue, final Instant expiresAt) {
        final String sql = String.join(" ",
            "INSERT INTO user_tokens (id, username, label, token_hash, expires_at)",
            "VALUES (?, ?, ?, ?, ?)"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, username);
            ps.setString(3, label);
            ps.setString(4, sha256(tokenValue));
            if (expiresAt != null) {
                ps.setTimestamp(5, Timestamp.from(expiresAt));
            } else {
                ps.setNull(5, java.sql.Types.TIMESTAMP);
            }
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to store token", ex);
        }
    }

    /**
     * List active (non-revoked) tokens for a user.
     * @param username Username
     * @return List of token info records
     */
    public List<TokenInfo> listByUser(final String username) {
        final String sql = String.join(" ",
            "SELECT id, label, expires_at, created_at",
            "FROM user_tokens",
            "WHERE username = ? AND revoked = FALSE",
            "ORDER BY created_at DESC"
        );
        final List<TokenInfo> result = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                final Timestamp exp = rs.getTimestamp("expires_at");
                result.add(new TokenInfo(
                    rs.getObject("id", UUID.class),
                    rs.getString("label"),
                    exp != null ? exp.toInstant() : null,
                    rs.getTimestamp("created_at").toInstant()
                ));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list tokens", ex);
        }
        return result;
    }

    /**
     * Revoke a token by ID for a given user.
     * @param id Token UUID
     * @param username Username (ownership check)
     * @return True if token was revoked
     */
    public boolean revoke(final UUID id, final String username) {
        final String sql =
            "UPDATE user_tokens SET revoked = TRUE WHERE id = ? AND username = ?";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to revoke token", ex);
        }
    }

    /**
     * Check if a token ID is valid (exists and not revoked).
     * @param id Token UUID (jti)
     * @return True if valid
     */
    public boolean isValid(final UUID id) {
        final String sql =
            "SELECT 1 FROM user_tokens WHERE id = ? AND revoked = FALSE";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeQuery().next();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to check token validity", ex);
        }
    }

    /**
     * SHA-256 hash of a token value.
     * @param value Token string
     * @return Hex-encoded hash
     */
    private static String sha256(final String value) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Token metadata record.
     */
    public static final class TokenInfo {

        private final UUID id;
        private final String label;
        private final Instant expiresAt;
        private final Instant createdAt;

        /**
         * Ctor.
         * @param id Token ID
         * @param label Human-readable label
         * @param expiresAt Expiry (null = permanent)
         * @param createdAt Creation timestamp
         */
        public TokenInfo(final UUID id, final String label,
            final Instant expiresAt, final Instant createdAt) {
            this.id = id;
            this.label = label;
            this.expiresAt = expiresAt;
            this.createdAt = createdAt;
        }

        public UUID id() {
            return this.id;
        }

        public String label() {
            return this.label;
        }

        public Instant expiresAt() {
            return this.expiresAt;
        }

        public Instant createdAt() {
            return this.createdAt;
        }
    }
}
