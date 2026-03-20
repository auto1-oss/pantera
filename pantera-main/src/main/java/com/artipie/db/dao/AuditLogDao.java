/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.db.dao;

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
