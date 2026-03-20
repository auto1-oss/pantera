/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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

/**
 * Database-backed authentication.
 * Authenticates users by querying the {@code users} table for username
 * and comparing the password against the stored {@code password_hash}.
 * Supports plain-text comparison (artipie provider) and SHA-256 hashing.
 *
 * @since 1.21
 */
public final class AuthFromDb implements Authentication {

    /**
     * Auth context name.
     */
    private static final String ARTIPIE = "artipie";

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
                // Only authenticate artipie-managed users (not SSO)
                if (!AuthFromDb.ARTIPIE.equals(provider)) {
                    return Optional.empty();
                }
                // Plain-text match (password stored as-is)
                if (hash.equals(pass)) {
                    return Optional.of(new AuthUser(name, AuthFromDb.ARTIPIE));
                }
                // SHA-256 match (password stored as hex digest)
                if (hash.equals(DigestUtils.sha256Hex(pass))) {
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

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }
}
