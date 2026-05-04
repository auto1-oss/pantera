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
import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.json.Json;
import javax.json.JsonObject;
import javax.sql.DataSource;

public final class SettingsDao {

    private final DataSource source;

    public SettingsDao(final DataSource source) {
        this.source = source;
    }

    public void put(final String key, final JsonObject value, final String actor) {
        final String sql = String.join(" ",
            "INSERT INTO settings (key, value, updated_by) VALUES (?, ?::jsonb, ?)",
            "ON CONFLICT (key) DO UPDATE SET value = ?::jsonb,",
            "updated_at = NOW(), updated_by = ?"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final String json = value.toString();
            ps.setString(1, key);
            ps.setString(2, json);
            ps.setString(3, actor);
            ps.setString(4, json);
            ps.setString(5, actor);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to put setting: " + key, ex);
        }
    }

    public Optional<JsonObject> get(final String key) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT value FROM settings WHERE key = ?"
             )) {
            ps.setString(1, key);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(
                    Json.createReader(new StringReader(rs.getString("value"))).readObject()
                );
            }
            return Optional.empty();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get setting: " + key, ex);
        }
    }

    public Map<String, JsonObject> listAll() {
        final Map<String, JsonObject> result = new LinkedHashMap<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT key, value FROM settings ORDER BY key"
             )) {
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.put(
                    rs.getString("key"),
                    Json.createReader(new StringReader(rs.getString("value"))).readObject()
                );
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list settings", ex);
        }
        return result;
    }

    public Map<String, JsonObject> getChangedSince(final Instant since) {
        final Map<String, JsonObject> result = new LinkedHashMap<>();
        final String sql = "SELECT key, value FROM settings WHERE updated_at > ? ORDER BY key";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, java.sql.Timestamp.from(since));
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.put(
                    rs.getString("key"),
                    Json.createReader(new StringReader(rs.getString("value"))).readObject()
                );
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to query changed settings", ex);
        }
        return result;
    }

    public void delete(final String key) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM settings WHERE key = ?"
             )) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to delete setting: " + key, ex);
        }
    }
}
