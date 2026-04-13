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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * DAO for UI-configurable authentication policy settings (auth_settings table).
 * @since 2.0.0
 */
public final class AuthSettingsDao {

    /**
     * Database data source.
     */
    private final DataSource source;

    /**
     * Ctor.
     * @param source Database data source
     */
    public AuthSettingsDao(final DataSource source) {
        this.source = source;
    }

    /**
     * Get the raw string value for a setting key.
     * @param key Setting key
     * @return Value if present, empty otherwise
     */
    public Optional<String> get(final String key) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT value FROM auth_settings WHERE key = ?"
             )) {
            ps.setString(1, key);
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Optional.of(rs.getString("value"));
            }
            return Optional.empty();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get auth setting: " + key, ex);
        }
    }

    /**
     * Get a setting as an integer, returning a default if absent or unparseable.
     * @param key Setting key
     * @param defaultValue Value to return when key is missing or value is not an integer
     * @return Parsed integer value, or defaultValue
     */
    public int getInt(final String key, final int defaultValue) {
        return this.get(key).map(val -> {
            try {
                return Integer.parseInt(val);
            } catch (final NumberFormatException ex) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    /**
     * Get a setting as a boolean, returning a default if absent.
     * Recognises {@code "true"} (case-insensitive) as {@code true};
     * any other value is {@code false}.
     * @param key Setting key
     * @param defaultValue Value to return when key is missing
     * @return Parsed boolean value, or defaultValue
     */
    public boolean getBool(final String key, final boolean defaultValue) {
        return this.get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    /**
     * Upsert a setting value.
     * @param key Setting key
     * @param value New value
     */
    public void put(final String key, final String value) {
        final String sql = String.join(" ",
            "INSERT INTO auth_settings (key, value) VALUES (?, ?)",
            "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to put auth setting: " + key, ex);
        }
    }

    /**
     * Get all settings as an ordered map (ordered by key).
     * @return Immutable snapshot of all settings
     */
    public Map<String, String> getAll() {
        final Map<String, String> result = new LinkedHashMap<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT key, value FROM auth_settings ORDER BY key"
             )) {
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to retrieve all auth settings", ex);
        }
        return result;
    }
}
