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
import javax.json.JsonObject;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AuditLogDao {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogDao.class);

    private static final String INSERT = String.join(
        " ",
        "INSERT INTO audit_log (actor, action, resource_type, resource_name,",
        "old_value, new_value) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)"
    );

    private final DataSource source;

    public AuditLogDao(final DataSource source) {
        this.source = source;
    }

    public void log(
        final String actor, final String action, final String resourceType,
        final String resourceName, final JsonObject oldValue, final JsonObject newValue
    ) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT)) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, resourceType);
            ps.setString(4, resourceName);
            ps.setString(5, oldValue != null ? oldValue.toString() : null);
            ps.setString(6, newValue != null ? newValue.toString() : null);
            ps.executeUpdate();
        } catch (final Exception ex) {
            LOG.error("Failed to write audit log: {} {} {}", action, resourceType, resourceName, ex);
        }
    }
}
