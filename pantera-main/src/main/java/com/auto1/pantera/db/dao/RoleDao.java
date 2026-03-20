/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

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
import com.auto1.pantera.settings.users.CrudRoles;

/**
 * PostgreSQL-backed role storage.
 * Drop-in replacement for ManageRoles.
 */
public final class RoleDao implements CrudRoles {

    private final DataSource source;

    public RoleDao(final DataSource source) {
        this.source = source;
    }

    @Override
    public JsonArray list() {
        final JsonArrayBuilder arr = Json.createArrayBuilder();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, permissions, enabled FROM roles ORDER BY name"
             )) {
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                arr.add(roleFromRow(rs));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list roles", ex);
        }
        return arr.build();
    }

    @Override
    public Optional<JsonObject> get(final String rname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name, permissions, enabled FROM roles WHERE name = ?"
             )) {
            ps.setString(1, rname);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(roleFromRow(rs));
            }
            return Optional.empty();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get role: " + rname, ex);
        }
    }

    @Override
    public void addOrUpdate(final JsonObject info, final String rname) {
        final String sql = String.join(" ",
            "INSERT INTO roles (name, permissions) VALUES (?, ?::jsonb)",
            "ON CONFLICT (name) DO UPDATE SET permissions = ?::jsonb,",
            "updated_at = NOW()"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final String permsJson = info.toString();
            ps.setString(1, rname);
            ps.setString(2, permsJson);
            ps.setString(3, permsJson);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to add/update role: " + rname, ex);
        }
    }

    @Override
    public void disable(final String rname) {
        setEnabled(rname, false);
    }

    @Override
    public void enable(final String rname) {
        setEnabled(rname, true);
    }

    @Override
    public void remove(final String rname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM roles WHERE name = ?"
             )) {
            ps.setString(1, rname);
            final int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new IllegalStateException("Role not found: " + rname);
            }
        } catch (final IllegalStateException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to remove role: " + rname, ex);
        }
    }

    private void setEnabled(final String rname, final boolean enabled) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE roles SET enabled = ?, updated_at = NOW() WHERE name = ?"
             )) {
            ps.setBoolean(1, enabled);
            ps.setString(2, rname);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to toggle role: " + rname, ex);
        }
    }

    private static JsonObject roleFromRow(final ResultSet rs) throws Exception {
        final JsonObject perms = Json.createReader(
            new StringReader(rs.getString("permissions"))
        ).readObject();
        final javax.json.JsonObjectBuilder bld = Json.createObjectBuilder()
            .add("name", rs.getString("name"))
            .add("enabled", rs.getBoolean("enabled"));
        // Merge permission keys at top level (not nested under "permissions")
        for (final String key : perms.keySet()) {
            bld.add(key, perms.get(key));
        }
        return bld.build();
    }
}
