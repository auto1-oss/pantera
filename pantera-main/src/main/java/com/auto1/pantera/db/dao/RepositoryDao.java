/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.db.dao;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.json.Json;
import javax.json.JsonStructure;
import javax.sql.DataSource;
import com.auto1.pantera.api.RepositoryName;
import com.auto1.pantera.settings.repo.CrudRepoSettings;

/**
 * PostgreSQL-backed repository configuration storage.
 * Drop-in replacement for ManageRepoSettings.
 */
public final class RepositoryDao implements CrudRepoSettings {

    private final DataSource source;

    public RepositoryDao(final DataSource source) {
        this.source = source;
    }

    @Override
    public Collection<String> listAll() {
        final List<String> result = new ArrayList<>();
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT name FROM repositories ORDER BY name"
             )) {
            final ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(rs.getString("name"));
            }
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to list repositories", ex);
        }
        return result;
    }

    @Override
    public Collection<String> list(final String uname) {
        // For now, returns all repos. User-scoped filtering will be added
        // when the API layer applies permission checks.
        return this.listAll();
    }

    @Override
    public boolean exists(final RepositoryName rname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT 1 FROM repositories WHERE name = ?"
             )) {
            ps.setString(1, rname.toString());
            return ps.executeQuery().next();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to check repo: " + rname, ex);
        }
    }

    @Override
    public JsonStructure value(final RepositoryName rname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT config FROM repositories WHERE name = ?"
             )) {
            ps.setString(1, rname.toString());
            final ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return Json.createReader(
                    new StringReader(rs.getString("config"))
                ).readObject();
            }
            throw new IllegalStateException("Repository not found: " + rname);
        } catch (final IllegalStateException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to get repo: " + rname, ex);
        }
    }

    @Override
    public void save(final RepositoryName rname, final JsonStructure value) {
        this.save(rname, value, (String) null);
    }

    @Override
    public void save(final RepositoryName rname, final JsonStructure value,
        final String actor) {
        final String type = extractType(value);
        final String sql = String.join(" ",
            "INSERT INTO repositories (name, type, config, created_by, updated_by)",
            "VALUES (?, ?, ?::jsonb, ?, ?)",
            "ON CONFLICT (name) DO UPDATE SET type = ?, config = ?::jsonb,",
            "updated_at = NOW(), updated_by = ?"
        );
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            final String json = value.toString();
            ps.setString(1, rname.toString());
            ps.setString(2, type);
            ps.setString(3, json);
            ps.setString(4, actor);
            ps.setString(5, actor);
            ps.setString(6, type);
            ps.setString(7, json);
            ps.setString(8, actor);
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to save repo: " + rname, ex);
        }
    }

    @Override
    public void delete(final RepositoryName rname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM repositories WHERE name = ?"
             )) {
            ps.setString(1, rname.toString());
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to delete repo: " + rname, ex);
        }
    }

    @Override
    public void move(final RepositoryName rname, final RepositoryName newrname) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "UPDATE repositories SET name = ?, updated_at = NOW() WHERE name = ?"
             )) {
            ps.setString(1, newrname.toString());
            ps.setString(2, rname.toString());
            ps.executeUpdate();
        } catch (final Exception ex) {
            throw new IllegalStateException(
                "Failed to move repo: " + rname + " -> " + newrname, ex
            );
        }
    }

    @Override
    public boolean hasSettingsDuplicates(final RepositoryName rname) {
        return false;
    }

    private static String extractType(final JsonStructure value) {
        try {
            return value.asJsonObject().getJsonObject("repo").getString("type");
        } catch (final Exception ex) {
            return "unknown";
        }
    }
}
