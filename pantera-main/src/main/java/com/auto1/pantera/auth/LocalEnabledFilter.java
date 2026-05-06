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

/**
 * Authentication wrapper that rejects any successful authentication
 * whose local-database user row is disabled.
 *
 * <p>Pantera has two separate auth paths:
 * <ul>
 *   <li><b>JWT</b> — session cookies and API bearer tokens flow through
 *       {@link UnifiedJwtAuthHandler}, which already consults a
 *       {@link UserEnabledCheck} per request.</li>
 *   <li><b>Basic auth</b> — CLI artifact pulls (docker pull, pip install,
 *       etc.) send HTTP basic credentials on every request. These flow
 *       through the {@link Authentication} chain, not the JWT filter.</li>
 * </ul>
 * Without this wrapper, a user disabled in Pantera but still valid at
 * an upstream SSO provider (Keycloak direct grant, Okta ROPC, ...) could
 * continue to pull artifacts via CLI even after an admin revoked them
 * in the UI. {@link AuthFromDb} already checks {@code enabled = true}
 * for local users, so the hole exists only for SSO/external providers.
 *
 * <p>Semantics:
 * <ul>
 *   <li>Inner provider returns empty → we return empty (failure).</li>
 *   <li>Inner returns a user AND local row exists AND is disabled →
 *       we return empty and log a warning.</li>
 *   <li>Inner returns a user AND local row exists AND is enabled →
 *       we pass through.</li>
 *   <li>Inner returns a user AND no local row exists yet → we pass
 *       through. Cases like SSO first-time provisioning create the
 *       row after authentication, so an absent row is not a block.
 *       The post-login provisioning step in the OAuth callback handler
 *       is responsible for the subsequent enabled gate.</li>
 *   <li>DB query fails → we fail closed (return empty) and log.</li>
 * </ul>
 *
 * @since 2.1.0
 */
public final class LocalEnabledFilter implements Authentication {

    /**
     * Query to load the {@code enabled} flag for a user by username.
     */
    private static final String SQL =
        "SELECT enabled FROM users WHERE username = ?";

    /**
     * Inner authentication whose results are filtered.
     */
    private final Authentication inner;

    /**
     * DataSource for the local user lookup.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param inner Inner authentication
     * @param source DataSource for the local users table
     */
    public LocalEnabledFilter(final Authentication inner, final DataSource source) {
        this.inner = inner;
        this.source = source;
    }

    @Override
    public Optional<AuthUser> user(final String username, final String password) {
        final Optional<AuthUser> result = this.inner.user(username, password);
        if (result.isEmpty()) {
            return result;
        }
        final Boolean enabled = this.loadEnabled(username);
        if (enabled == null) {
            // No local row yet — SSO first-time provisioning path.
            // Allow through; the OAuth callback handler owns the
            // enabled gate for the subsequent provisioning step.
            return result;
        }
        if (!enabled) {
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("Authentication rejected: user disabled in Pantera")
                .eventCategory("authentication")
                .eventAction("login")
                .eventOutcome("failure")
                .field("user.name", username)
                .log();
            return Optional.empty();
        }
        return result;
    }

    @Override
    public boolean canHandle(final String username) {
        return this.inner.canHandle(username);
    }

    @Override
    public boolean isAuthoritative(final String username) {
        return this.inner.isAuthoritative(username);
    }

    /**
     * Load the enabled flag for a user from the database.
     * @param username Username
     * @return {@code true} if enabled, {@code false} if disabled,
     *     {@code null} if no row exists
     */
    private Boolean loadEnabled(final String username) {
        try (Connection conn = this.source.getConnection();
            PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getBoolean("enabled");
            }
        } catch (final Exception err) {
            // Fail closed: treat DB failure as "disabled" so that a
            // database outage does not silently widen the attack
            // surface by letting every request through.
            EcsLogger.warn("com.auto1.pantera.auth")
                .message("LocalEnabledFilter failed to load user; failing closed")
                .eventCategory("authentication")
                .eventAction("user_enabled_check")
                .eventOutcome("failure")
                .field("user.name", username)
                .error(err)
                .log();
            return false;
        }
    }
}
