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
package com.auto1.pantera.repojob;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;

/**
 * Role grants that name one repository exactly.
 *
 * <p>{@code roles.permissions} is a document of permission types, each an
 * object keyed by repository name ({@code {"adapter_basic_permissions":
 * {"maven": ["read"]}}}). A grant keyed by a pattern ({@code *},
 * {@code maven-*}) names no single repository and is never touched: the
 * renamed repository matches it by its new name or not at all.</p>
 *
 * @since 2.2.14
 */
final class RoleGrants {

    /**
     * Move every grant keyed by the old name to the new name. A role that
     * already grants on the new name keeps that grant.
     * @param conn Connection inside a transaction
     * @param name Old repository name
     * @param target New repository name
     * @return Names of the roles changed
     * @throws SQLException On error
     */
    List<String> rename(final Connection conn, final String name, final String target)
        throws SQLException {
        final List<String> changed = new ArrayList<>();
        for (final Map.Entry<String, JsonObject> role : RoleGrants.rolesNaming(conn, name).entrySet()) {
            final JsonObjectBuilder doc = Json.createObjectBuilder();
            boolean touched = false;
            for (final Map.Entry<String, JsonValue> type : role.getValue().entrySet()) {
                if (RoleGrants.keyed(type.getValue(), name)) {
                    final JsonObject grants = type.getValue().asJsonObject();
                    final JsonObjectBuilder moved = Json.createObjectBuilder();
                    for (final Map.Entry<String, JsonValue> grant : grants.entrySet()) {
                        if (!grant.getKey().equals(name)) {
                            moved.add(grant.getKey(), grant.getValue());
                        } else if (!grants.containsKey(target)) {
                            moved.add(target, grant.getValue());
                        }
                    }
                    doc.add(type.getKey(), moved);
                    touched = true;
                } else {
                    doc.add(type.getKey(), type.getValue());
                }
            }
            if (touched) {
                RoleGrants.save(conn, role.getKey(), doc.build());
                changed.add(role.getKey());
            }
        }
        return changed;
    }

    /**
     * Take every grant keyed by a repository name out of the roles.
     * @param conn Connection inside a transaction
     * @param name Repository name
     * @return The grants taken, as {@code {role, type, grant}} records
     * @throws SQLException On error
     */
    JsonArray detach(final Connection conn, final String name) throws SQLException {
        final JsonArrayBuilder taken = Json.createArrayBuilder();
        for (final Map.Entry<String, JsonObject> role : RoleGrants.rolesNaming(conn, name).entrySet()) {
            final JsonObjectBuilder doc = Json.createObjectBuilder();
            boolean touched = false;
            for (final Map.Entry<String, JsonValue> type : role.getValue().entrySet()) {
                if (RoleGrants.keyed(type.getValue(), name)) {
                    final JsonObject grants = type.getValue().asJsonObject();
                    taken.add(
                        Json.createObjectBuilder()
                            .add("role", role.getKey())
                            .add("type", type.getKey())
                            .add("grant", grants.get(name))
                    );
                    final JsonObjectBuilder rest = Json.createObjectBuilder();
                    for (final Map.Entry<String, JsonValue> grant : grants.entrySet()) {
                        if (!grant.getKey().equals(name)) {
                            rest.add(grant.getKey(), grant.getValue());
                        }
                    }
                    doc.add(type.getKey(), rest);
                    touched = true;
                } else {
                    doc.add(type.getKey(), type.getValue());
                }
            }
            if (touched) {
                RoleGrants.save(conn, role.getKey(), doc.build());
            }
        }
        return taken.build();
    }

    /**
     * Give grants taken by {@link #detach} back, keyed by the given name.
     * A role that no longer exists, or that grants on the name again, is
     * left alone.
     * @param conn Connection inside a transaction
     * @param name Repository name to key the grants by
     * @param taken Records produced by {@link #detach}
     * @return Names of the roles changed
     * @throws SQLException On error
     */
    List<String> attach(final Connection conn, final String name, final JsonArray taken)
        throws SQLException {
        final List<String> changed = new ArrayList<>();
        for (final JsonValue item : taken) {
            final JsonObject record = item.asJsonObject();
            final String role = record.getString("role");
            final String type = record.getString("type");
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT permissions FROM roles WHERE name = ? FOR UPDATE"
            )) {
                stmt.setString(1, role);
                try (ResultSet rows = stmt.executeQuery()) {
                    if (!rows.next()) {
                        continue;
                    }
                    final JsonObject doc = RoleGrants.json(rows.getString(1));
                    final JsonObject grants = doc.containsKey(type)
                        && doc.get(type).getValueType() == JsonValue.ValueType.OBJECT
                        ? doc.getJsonObject(type) : JsonValue.EMPTY_JSON_OBJECT;
                    if (!grants.containsKey(name)) {
                        RoleGrants.save(
                            conn, role,
                            Json.createObjectBuilder(doc).add(
                                type,
                                Json.createObjectBuilder(grants).add(name, record.get("grant"))
                            ).build()
                        );
                        if (!changed.contains(role)) {
                            changed.add(role);
                        }
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Names of the roles in records produced by {@link #detach}.
     * @param taken Records
     * @return Distinct role names
     */
    List<String> roles(final JsonArray taken) {
        final List<String> res = new ArrayList<>();
        for (final JsonValue item : taken) {
            final String role = item.asJsonObject().getString("role");
            if (!res.contains(role)) {
                res.add(role);
            }
        }
        return res;
    }

    /**
     * Roles with at least one permission type keyed by a repository name.
     * @param conn Connection
     * @param name Repository name
     * @return Permission documents by role name, rows locked
     * @throws SQLException On error
     */
    private static Map<String, JsonObject> rolesNaming(final Connection conn, final String name)
        throws SQLException {
        final Map<String, JsonObject> res = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
            "SELECT name, permissions FROM roles WHERE EXISTS ("
                + "SELECT 1 FROM jsonb_each(permissions) AS perm(ptype, grants)"
                + " WHERE jsonb_typeof(perm.grants) = 'object' AND jsonb_exists(perm.grants, ?)"
                + ") ORDER BY name FOR UPDATE"
        )) {
            stmt.setString(1, name);
            try (ResultSet rows = stmt.executeQuery()) {
                while (rows.next()) {
                    res.put(rows.getString(1), RoleGrants.json(rows.getString(2)));
                }
            }
        }
        return res;
    }

    /**
     * Whether a permission type is an object with a grant for the name.
     * @param value Value of a permission type
     * @param name Repository name
     * @return True if so
     */
    private static boolean keyed(final JsonValue value, final String name) {
        return value.getValueType() == JsonValue.ValueType.OBJECT
            && value.asJsonObject().containsKey(name);
    }

    /**
     * Write a role's permission document.
     * @param conn Connection
     * @param role Role name
     * @param doc Permission document
     * @throws SQLException On error
     */
    private static void save(final Connection conn, final String role, final JsonObject doc)
        throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
            "UPDATE roles SET permissions = ?::jsonb, updated_at = NOW() WHERE name = ?"
        )) {
            stmt.setString(1, doc.toString());
            stmt.setString(2, role);
            stmt.executeUpdate();
        }
    }

    /**
     * Parse a JSON object.
     * @param text JSON text
     * @return Object
     */
    private static JsonObject json(final String text) {
        return Json.createReader(new StringReader(text)).readObject();
    }
}
