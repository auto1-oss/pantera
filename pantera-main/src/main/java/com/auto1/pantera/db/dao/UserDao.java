/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

import com.auto1.pantera.http.log.EcsLogger;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.sql.DataSource;
import com.auto1.pantera.settings.users.CrudUsers;

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
                final String pass;
                if (info.containsKey("pass")) {
                    pass = info.getString("pass");
                } else if (info.containsKey("password")) {
                    pass = info.getString("password");
                } else {
                    pass = null;
                }
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
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE users SET password_hash = ?, updated_at = NOW() WHERE username = ?"
             )) {
            ps.setString(1, newPass);
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
                .message("updateUserRoles: cleared existing roles")
                .eventCategory("user")
                .eventAction("role_assignment")
                .field("user.name", uname)
                .field("user.id", userId)
                .field("deleted.count", deleted)
                .log();
        }
        // Insert new role assignments
        if (roles != null && !roles.isEmpty()) {
            final java.util.List<String> roleNames = new java.util.ArrayList<>();
            for (int idx = 0; idx < roles.size(); idx++) {
                roleNames.add(roles.getString(idx));
            }
            EcsLogger.info("com.auto1.pantera.db")
                .message("updateUserRoles: assigning roles")
                .eventCategory("user")
                .eventAction("role_assignment")
                .field("user.name", uname)
                .field("user.id", userId)
                .field("roles", String.join(",", roleNames))
                .field("roles.count", roleNames.size())
                .log();
            // Auto-create roles that don't exist yet (e.g. SSO default "reader")
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO roles (name, permissions) VALUES (?, '{}'::jsonb) "
                + "ON CONFLICT (name) DO NOTHING")) {
                for (final String roleName : roleNames) {
                    ps.setString(1, roleName);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_roles (user_id, role_id) "
                + "SELECT ?, id FROM roles WHERE name = ?")) {
                for (final String roleName : roleNames) {
                    ps.setInt(1, userId);
                    ps.setString(2, roleName);
                    final int inserted = ps.executeUpdate();
                    EcsLogger.info("com.auto1.pantera.db")
                        .message("updateUserRoles: role assignment result")
                        .eventCategory("user")
                        .eventAction("role_assignment")
                        .field("user.name", uname)
                        .field("role.name", roleName)
                        .field("rows.inserted", inserted)
                        .log();
                }
            }
        } else {
            EcsLogger.warn("com.auto1.pantera.db")
                .message("updateUserRoles: no roles to assign")
                .eventCategory("user")
                .eventAction("role_assignment")
                .field("user.name", uname)
                .field("roles.null", roles == null)
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
