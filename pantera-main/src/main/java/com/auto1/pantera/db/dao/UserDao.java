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

import com.auto1.pantera.http.log.EcsLogger;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.sql.DataSource;
import com.auto1.pantera.settings.users.CrudUsers;
import org.mindrot.jbcrypt.BCrypt;

/**
 * PostgreSQL-backed user storage.
 * Drop-in replacement for ManageUsers.
 */
public final class UserDao implements CrudUsers {

    private final DataSource source;

    public UserDao(final DataSource source) {
        this.source = source;
    }

    @Override
    public JsonArray list() {
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        final String sql = String.join(" ",
            "SELECT u.username, u.email, u.enabled, u.auth_provider,",
            "COALESCE(json_agg(r.name) FILTER (WHERE r.name IS NOT NULL), '[]') AS roles",
            "FROM users u",
            "LEFT JOIN user_roles ur ON u.id = ur.user_id",
            "LEFT JOIN roles r ON ur.role_id = r.id",
            "GROUP BY u.id ORDER BY u.username"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                arr.add(userFromRow(rs));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list users", ex);
        }
        return arr.build();
    }

    /**
     * Return a paginated, filtered, sorted page of users.
     * @param query Optional search string matched against username and email (ILIKE).
     * @param sortField Column to sort by; validated against allowlist, defaults to "username".
     * @param ascending Sort direction.
     * @param limit Page size.
     * @param offset Row offset.
     * @return PagedResult containing the requested page and the unfiltered total count.
     */
    public PagedResult<JsonObject> listPaged(final String query, final String sortField,
        final boolean ascending, final int limit, final int offset) {
        final Set<String> allowed = Set.of("username", "email", "enabled", "auth_provider");
        final String col = allowed.contains(sortField) ? sortField : "username";
        final String dir = ascending ? "ASC" : "DESC";
        final String sql = String.join(" ",
            "SELECT u.username, u.email, u.enabled, u.auth_provider,",
            "COALESCE(json_agg(r.name) FILTER (WHERE r.name IS NOT NULL), '[]') AS roles,",
            "COUNT(*) OVER() AS total_count",
            "FROM users u",
            "LEFT JOIN user_roles ur ON u.id = ur.user_id",
            "LEFT JOIN roles r ON ur.role_id = r.id",
            "WHERE (? IS NULL OR LOWER(u.username) LIKE ? OR LOWER(u.email) LIKE ?)",
            "GROUP BY u.id",
            "ORDER BY u." + col + " " + dir,
            "LIMIT ? OFFSET ?"
        );
        final String pattern = query == null ? null : "%" + query.toLowerCase() + "%";
        final List<JsonObject> items = new ArrayList<>();
        int total = 0;
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setInt(4, limit);
            ps.setInt(5, offset);
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (total == 0) {
                    total = rs.getInt("total_count");
                }
                items.add(userFromRow(rs));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list users (paged)", ex);
        }
        return new PagedResult<>(items, total);
    }

    @Override
    public Optional<JsonObject> get(final String uname) {
        final String sql = String.join(" ",
            "SELECT u.username, u.email, u.enabled, u.auth_provider,",
            "COALESCE(json_agg(r.name) FILTER (WHERE r.name IS NOT NULL), '[]') AS roles",
            "FROM users u",
            "LEFT JOIN user_roles ur ON u.id = ur.user_id",
            "LEFT JOIN roles r ON ur.role_id = r.id",
            "WHERE u.username = ?",
            "GROUP BY u.id"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uname);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(userFromRow(rs));
            }
            return Optional.empty();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get user: " + uname, ex);
        }
    }

    @Override
    public void addOrUpdate(final JsonObject info, final String uname) {
        final String sql = String.join(" ",
            "INSERT INTO users (username, password_hash, email, auth_provider)",
            "VALUES (?, ?, ?, ?)",
            "ON CONFLICT (username) DO UPDATE SET",
            "password_hash = COALESCE(?, users.password_hash),",
            "email = COALESCE(?, users.email),",
            "auth_provider = COALESCE(?, users.auth_provider),",
            "updated_at = NOW()"
        );
        try (Connection conn = this.source.getConnection()) {
            conn.setAutoCommit(false);
            try {
                final String rawPass;
                if (info.containsKey("pass")) {
                    rawPass = info.getString("pass");
                } else if (info.containsKey("password")) {
                    rawPass = info.getString("password");
                } else {
                    rawPass = null;
                }
                final String pass = rawPass != null
                    ? BCrypt.hashpw(rawPass, BCrypt.gensalt()) : null;
                final String email = info.containsKey("email")
                    ? info.getString("email") : null;
                // Map password format types (plain, sha256) to "local" provider.
                // Only actual provider names (keycloak, okta) are stored literally.
                final String rawType = info.containsKey("type")
                    ? info.getString("type") : "local";
                final String provider = "plain".equals(rawType) || "sha256".equals(rawType)
                    ? "local" : rawType;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, uname);
                    ps.setString(2, pass);
                    ps.setString(3, email);
                    ps.setString(4, provider);
                    ps.setString(5, pass);
                    ps.setString(6, email);
                    ps.setString(7, provider);
                    ps.executeUpdate();
                }
                // Update role assignments if roles are provided
                if (info.containsKey("roles")) {
                    updateUserRoles(conn, uname, info.getJsonArray("roles"));
                }
                conn.commit();
            } catch (final Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to add/update user: " + uname, ex);
        }
    }

    @Override
    public void disable(final String uname) {
        setEnabled(uname, false);
    }

    @Override
    public void enable(final String uname) {
        setEnabled(uname, true);
    }

    @Override
    public void remove(final String uname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM users WHERE username = ?"
             )) {
            ps.setString(1, uname);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to remove user: " + uname, ex);
        }
    }

    @Override
    public void alterPassword(final String uname, final JsonObject info) {
        final String newPass = info.getString("new_pass");
        final String hashed = BCrypt.hashpw(newPass, BCrypt.gensalt());
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET password_hash = ?, updated_at = NOW() WHERE username = ?"
             )) {
            ps.setString(1, hashed);
            ps.setString(2, uname);
            final int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IllegalStateException("User not found: " + uname);
            }
        } catch (final IllegalStateException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to alter password: " + uname, ex);
        }
    }

    private void setEnabled(final String uname, final boolean enabled) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET enabled = ?, updated_at = NOW() WHERE username = ?"
             )) {
            ps.setBoolean(1, enabled);
            ps.setString(2, uname);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to toggle user: " + uname, ex);
        }
    }

    private static void updateUserRoles(final Connection conn, final String uname,
        final JsonArray roles) throws Exception {
        // Get user ID
        final int userId;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT id FROM users WHERE username = ?")) {
            ps.setString(1, uname);
            final ResultSet rs = ps.executeQuery();
            if (!rs.next()) {
                EcsLogger.warn("com.auto1.pantera.db")
                    .message("updateUserRoles: user not found in DB after insert")
                    .eventCategory("user")
                    .eventAction("role_assignment")
                    .eventOutcome("failure")
                    .field("user.name", uname)
                    .log();
                return;
            }
            userId = rs.getInt("id");
        }
        // Delete existing role assignments
        try (PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM user_roles WHERE user_id = ?")) {
            ps.setInt(1, userId);
            final int deleted = ps.executeUpdate();
            EcsLogger.info("com.auto1.pantera.db")
                .message("updateUserRoles: cleared existing roles, " + deleted + " records removed")
                .eventCategory("user")
                .eventAction("role_assignment")
                .field("user.name", uname)
                .field("user.id", userId)
                .log();
        }
        // Insert new role assignments
        if (roles != null && !roles.isEmpty()) {
            final java.util.List<String> roleNames = new java.util.ArrayList<>();
            for (int idx = 0; idx < roles.size(); idx++) {
                roleNames.add(roles.getString(idx));
            }
            EcsLogger.info("com.auto1.pantera.db")
                .message("updateUserRoles: assigning " + roleNames.size() + " roles [" + String.join(",", roleNames) + "]")
                .eventCategory("user")
                .eventAction("role_assignment")
                .field("user.name", uname)
                .field("user.id", userId)
                .log();
            // Auto-create roles that don't exist yet (e.g. SSO default "reader")
            // Uses batch INSERT instead of N individual round-trips
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO roles (name, permissions) VALUES (?, '{}'::jsonb) "
                + "ON CONFLICT (name) DO NOTHING")) {
                for (final String roleName : roleNames) {
                    ps.setString(1, roleName);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // Batch assign all roles in one round-trip per role
            // (uses addBatch to minimize network overhead)
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE name = ?")) {
                for (final String roleName : roleNames) {
                    ps.setInt(1, userId);
                    ps.setString(2, roleName);
                    ps.addBatch();
                }
                final int[] results = ps.executeBatch();
                EcsLogger.info("com.auto1.pantera.db")
                    .message("updateUserRoles: batch role assignment complete, batch_size=" + results.length + " roles=[" + String.join(",", roleNames) + "]")
                    .eventCategory("user")
                    .eventAction("role_assignment")
                    .field("user.name", uname)
                    .log();
            }
        } else {
            EcsLogger.warn("com.auto1.pantera.db")
                .message("updateUserRoles: no roles to assign (roles_null=" + (roles == null) + ")")
                .eventCategory("user")
                .eventAction("role_assignment")
                .field("user.name", uname)
                .log();
        }
    }

    private static JsonObject userFromRow(final ResultSet rs) throws Exception {
        final JsonObjectBuilder bld = Json.createObjectBuilder()
            .add("name", rs.getString("username"))
            .add("enabled", rs.getBoolean("enabled"))
            .add("auth_provider", rs.getString("auth_provider"));
        final String email = rs.getString("email");
        if (email != null) {
            bld.add("email", email);
        }
        final String rolesJson = rs.getString("roles");
        if (rolesJson != null) {
            bld.add("roles",
                Json.createReader(new StringReader(rolesJson)).readArray());
        }
        return bld.build();
    }
}
