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
import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;

public final class AuthProviderDao {

    private final DataSource source;

    public AuthProviderDao(final DataSource source) {
        this.source = source;
    }

    public List<JsonObject> list() {
        return query("SELECT id, type, priority, config, enabled FROM auth_providers ORDER BY priority");
    }

    public List<JsonObject> listEnabled() {
        return query("SELECT id, type, priority, config, enabled FROM auth_providers WHERE enabled = TRUE ORDER BY priority");
    }

    /**
     * UPSERT by type. Uses ON CONFLICT (type) since type has UNIQUE constraint.
     */
    public void put(final String type, final int priority, final JsonObject config) {
        final String sql = String.join(" ",
            "INSERT INTO auth_providers (type, priority, config) VALUES (?, ?, ?::jsonb)",
            "ON CONFLICT (type) DO UPDATE SET priority = ?, config = ?::jsonb"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final String json = config.toString();
            ps.setString(1, type);
            ps.setInt(2, priority);
            ps.setString(3, json);
            ps.setInt(4, priority);
            ps.setString(5, json);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to put auth provider: " + type, ex);
        }
    }

    /**
     * Update the config JSON for an existing auth provider by ID.
     * @param id Provider ID
     * @param config New config JSON
     */
    public void updateConfig(final int id, final JsonObject config) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE auth_providers SET config = ?::jsonb WHERE id = ?"
             )) {
            ps.setString(1, config.toString());
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to update auth provider config: " + id, ex);
        }
    }

    public void delete(final int id) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM auth_providers WHERE id = ?"
             )) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to delete auth provider: " + id, ex);
        }
    }

    /**
     * Insert a provider only if no row with this type already exists.
     * Used to bootstrap the default {@code local} provider on startup
     * without overwriting admin-edited config or enable state.
     *
     * @param type Provider type
     * @param priority Priority for ordering
     * @param config Initial config JSON
     * @return true if a new row was inserted, false if a row already existed
     */
    public boolean ensureExists(final String type, final int priority, final JsonObject config) {
        final String sql = String.join(" ",
            "INSERT INTO auth_providers (type, priority, config) VALUES (?, ?, ?::jsonb)",
            "ON CONFLICT (type) DO NOTHING"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setInt(2, priority);
            ps.setString(3, config.toString());
            return ps.executeUpdate() > 0;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to ensure auth provider exists: " + type, ex);
        }
    }

    /**
     * Look up the {@code type} of a provider by its ID. Returns null if not found.
     * Used to check whether a delete request targets the protected local provider.
     *
     * @param id Provider ID
     * @return Provider type string, or null if no row matches
     */
    public String typeOf(final int id) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT type FROM auth_providers WHERE id = ?"
             )) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to look up auth provider type: " + id, ex);
        }
    }

    public void enable(final int id) {
        setEnabled(id, true);
    }

    public void disable(final int id) {
        setEnabled(id, false);
    }

    private void setEnabled(final int id, final boolean enabled) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE auth_providers SET enabled = ? WHERE id = ?"
             )) {
            ps.setBoolean(1, enabled);
            ps.setInt(2, id);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to toggle auth provider: " + id, ex);
        }
    }

    private List<JsonObject> query(final String sql) {
        final List<JsonObject> result = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(Json.createObjectBuilder()
                    .add("id", rs.getInt("id"))
                    .add("type", rs.getString("type"))
                    .add("priority", rs.getInt("priority"))
                    .add("config", Json.createReader(
                        new StringReader(rs.getString("config"))).readObject())
                    .add("enabled", rs.getBoolean("enabled"))
                    .build());
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to query auth providers", ex);
        }
        return result;
    }
}
