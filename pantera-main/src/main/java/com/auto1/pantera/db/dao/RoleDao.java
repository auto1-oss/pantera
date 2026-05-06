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

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Return a paginated, filtered, sorted page of roles.
     * @param query Optional search string matched against name (ILIKE).
     * @param sortField Column to sort by; validated against allowlist, defaults to "name".
     * @param ascending Sort direction.
     * @param limit Page size.
     * @param offset Row offset.
     * @return PagedResult containing the requested page and the unfiltered total count.
     */
    public PagedResult<JsonObject> listPaged(final String query, final String sortField,
        final boolean ascending, final int limit, final int offset) {
        final Set<String> allowed = Set.of("name", "enabled");
        final String col = allowed.contains(sortField) ? sortField : "name";
        final String dir = ascending ? "ASC" : "DESC";
        final String sql = String.join(" ",
            "SELECT name, permissions, enabled, COUNT(*) OVER() AS total_count",
            "FROM roles",
            "WHERE (? IS NULL OR LOWER(name) LIKE ?)",
            "ORDER BY " + col + " " + dir,
            "LIMIT ? OFFSET ?"
        );
        final String pattern = query == null ? null : "%" + query.toLowerCase(Locale.ROOT) + "%";
        final List<JsonObject> items = new ArrayList<>();
        int total = 0;
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                if (total == 0) {
                    total = rs.getInt("total_count");
                }
                items.add(roleFromRow(rs));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list roles (paged)", ex);
        }
        return new PagedResult<>(items, total);
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
