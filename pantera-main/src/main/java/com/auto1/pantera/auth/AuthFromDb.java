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
package com.auto1.pantera.auth;

import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.log.EcsLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.commons.codec.digest.DigestUtils;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Database-backed authentication.
 * Authenticates users by querying the {@code users} table for username
 * and comparing the password against the stored {@code password_hash}.
 * Supports plain-text comparison (pantera provider) and SHA-256 hashing.
 *
 * @since 1.21
 */
public final class AuthFromDb implements Authentication {

    /**
     * Auth context name.
     */
    private static final String ARTIPIE = "local";

    /**
     * SQL query to fetch password hash and provider for an enabled user.
     */
    private static final String SQL = String.join(" ",
        "SELECT password_hash, auth_provider",
        "FROM users",
        "WHERE username = ? AND enabled = true"
    );

    /**
     * Database data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database data source
     */
    public AuthFromDb(final DataSource source) {
        this.source = source;
    }

    /**
     * SQL query to check whether a username exists as a local-provider
     * user. Used by {@link #isAuthoritative(String)} to tell the
     * authentication chain that SSO fallthrough is forbidden for this
     * username.
     */
    private static final String EXISTS_SQL = String.join(" ",
        "SELECT 1 FROM users",
        "WHERE username = ? AND enabled = true AND auth_provider = 'local'",
        "LIMIT 1"
    );

    @Override
    public boolean isAuthoritative(final String name) {
        // Claim authority over every enabled local-auth user in the DB so
        // the Joined chain stops on failure instead of falling through to
        // SSO providers that might accept a weak password for the same
        // username. Non-local users (SSO-provisioned) and unknown usernames
        // return false — the chain continues through SSO providers for them.
        if (name == null || name.isEmpty()) {
            return false;
        }
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(AuthFromDb.EXISTS_SQL)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("isAuthoritative lookup failed — defaulting to false")
                .eventCategory("authentication")
                .eventAction("db_auth_authoritative")
                .field("user.name", name)
                .error(ex)
                .log();
            return false;
        }
    }

    @Override
    public Optional<AuthUser> user(final String name, final String pass) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(AuthFromDb.SQL)) {
            ps.setString(1, name);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                final String hash = rs.getString("password_hash");
                final String provider = rs.getString("auth_provider");
                if (hash == null || hash.isEmpty()) {
                    return Optional.empty();
                }
                // Only authenticate pantera-managed users (not SSO)
                if (!AuthFromDb.ARTIPIE.equals(provider)) {
                    return Optional.empty();
                }
                // Bcrypt match
                if (hash.startsWith("$2") && BCrypt.checkpw(pass, hash)) {
                    return Optional.of(new AuthUser(name, AuthFromDb.ARTIPIE));
                }
                // SHA-256 match (legacy passwords from YAML migration)
                // Transparently upgrade to bcrypt on successful login.
                if (hash.equals(DigestUtils.sha256Hex(pass))) {
                    this.upgradeHashToBcrypt(name, pass);
                    return Optional.of(new AuthUser(name, AuthFromDb.ARTIPIE));
                }
            }
            return Optional.empty();
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.auth")
                .message("Failed to authenticate user from database")
                .eventCategory("authentication")
                .eventAction("db_auth")
                .eventOutcome("failure")
                .field("user.name", name)
                .error(ex)
                .log();
            return Optional.empty();
        }
    }

    /**
     * Transparently upgrade a legacy SHA-256 hash to bcrypt.
     * Runs asynchronously to avoid slowing down the login response.
     *
     * @param username User whose hash to upgrade
     * @param plaintext Current plaintext password (already verified)
     */
    private void upgradeHashToBcrypt(final String username, final String plaintext) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET password_hash = ?, updated_at = NOW() "
                     + "WHERE username = ? AND password_hash = ?"
             )) {
            final String bcryptHash = BCrypt.hashpw(plaintext, BCrypt.gensalt());
            ps.setString(1, bcryptHash);
            ps.setString(2, username);
            ps.setString(3, DigestUtils.sha256Hex(plaintext));
            ps.executeUpdate();
            EcsLogger.info("com.auto1.pantera.auth")
                .message("Upgraded password hash from SHA-256 to bcrypt")
                .eventCategory("authentication")
                .eventAction("hash_upgrade")
                .field("user.name", username)
                .log();
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Failed to upgrade password hash to bcrypt")
                .eventCategory("authentication")
                .eventAction("hash_upgrade")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(ex)
                .log();
        }
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }
}
