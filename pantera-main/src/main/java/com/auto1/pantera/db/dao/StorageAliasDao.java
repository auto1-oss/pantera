/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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

public final class StorageAliasDao {

    private final DataSource source;

    public StorageAliasDao(final DataSource source) {
        this.source = source;
    }

    public List<JsonObject> listGlobal() {
        return listByRepo(null);
    }

    public List<JsonObject> listForRepo(final String repoName) {
        return listByRepo(repoName);
    }

    public void put(final String name, final String repoName, final JsonObject config) {
        final String sql;
        if (repoName == null) {
            sql = String.join(" ",
                "INSERT INTO storage_aliases (name, repo_name, config)",
                "VALUES (?, NULL, ?::jsonb)",
                "ON CONFLICT (name) WHERE repo_name IS NULL",
                "DO UPDATE SET config = ?::jsonb, updated_at = NOW()"
            );
        } else {
            sql = String.join(" ",
                "INSERT INTO storage_aliases (name, repo_name, config)",
                "VALUES (?, ?, ?::jsonb)",
                "ON CONFLICT (name, repo_name)",
                "DO UPDATE SET config = ?::jsonb, updated_at = NOW()"
            );
        }
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final String json = config.toString();
            ps.setString(1, name);
            if (repoName == null) {
                ps.setString(2, json);
                ps.setString(3, json);
            } else {
                ps.setString(2, repoName);
                ps.setString(3, json);
                ps.setString(4, json);
            }
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to put alias: " + name, ex);
        }
    }

    public void delete(final String name, final String repoName) {
        final String sql = repoName == null
            ? "DELETE FROM storage_aliases WHERE name = ? AND repo_name IS NULL"
            : "DELETE FROM storage_aliases WHERE name = ? AND repo_name = ?";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            if (repoName != null) {
                ps.setString(2, repoName);
            }
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to delete alias: " + name, ex);
        }
    }

    /**
     * Find repos whose config JSONB references this alias name via
     * the `repo.storage` field.
     */
    public List<String> findReposUsing(final String aliasName) {
        final List<String> result = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM repositories WHERE config->'repo'->>'storage' = ?"
             )) {
            ps.setString(1, aliasName);
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("name"));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to find repos using: " + aliasName, ex);
        }
        return result;
    }

    private List<JsonObject> listByRepo(final String repoName) {
        final List<JsonObject> result = new ArrayList<>();
        final String sql = repoName == null
            ? "SELECT name, config FROM storage_aliases WHERE repo_name IS NULL ORDER BY name"
            : "SELECT name, config FROM storage_aliases WHERE repo_name = ? ORDER BY name";
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (repoName != null) {
                ps.setString(1, repoName);
            }
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(Json.createObjectBuilder()
                    .add("name", rs.getString("name"))
                    .add("config", Json.createReader(
                        new StringReader(rs.getString("config"))).readObject())
                    .build());
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list aliases", ex);
        }
        return result;
    }
}
