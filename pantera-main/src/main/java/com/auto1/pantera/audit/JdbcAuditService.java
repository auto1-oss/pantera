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
package com.auto1.pantera.audit;

import com.auto1.pantera.http.log.EcsLogger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link AuditService} implementation — the T-S04 persistence
 * layer.
 *
 * <p>Writes through the supplied {@link DataSource} on the
 * {@link ForkJoinPool#commonPool()} so admin handlers stay event-loop
 * friendly. Failures are caught, logged at WARN, and surfaced via the
 * returned future — they never throw back to the caller. The audit
 * write is non-critical for the admin operation; a failed write should
 * be visible in monitoring but must not break the user-facing action.
 *
 * <p>The {@code details} payload is rendered as compact JSON using the
 * minimal encoder in {@link #renderJson(Map)} — no Jackson dependency to
 * keep the pantera-main module's existing weight unchanged.
 *
 * @since 2.2.0
 */
public final class JdbcAuditService implements AuditService {

    /** Logger name for warnings on persistence failure. */
    private static final String LOGGER = "com.auto1.pantera.audit";

    /**
     * Insert statement. The {@code created_at} column defaults to {@code NOW()}
     * — we set it explicitly when {@link AuditEvent#timestamp()} is non-null
     * so reproducible / synthetic events keep their requested timestamp.
     */
    private static final String INSERT = "INSERT INTO audit_log ("
        + "created_at, actor, action, resource_type, resource_name,"
        + " details, success, ip_address"
        + ") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)";

    /** Pool used to write through. */
    private final DataSource source;

    /**
     * Ctor.
     *
     * @param source JDBC data source — never {@code null}
     */
    public JdbcAuditService(final DataSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public CompletableFuture<Void> record(final AuditEvent event) {
        Objects.requireNonNull(event, "event");
        return CompletableFuture.runAsync(
            () -> this.writeEvent(event), ForkJoinPool.commonPool()
        ).whenComplete((ignored, err) -> {
            if (err != null) {
                EcsLogger.warn(LOGGER)
                    .message("Failed to persist audit event")
                    .eventCategory("database")
                    .eventAction("audit_write")
                    .eventOutcome("failure")
                    .field("event.action", event.action())
                    .field("user.name", event.actor())
                    .error(err)
                    .field("log.source", "application")
                    .log();
            }
        });
    }

    /**
     * Blocking write — runs on the ForkJoin pool.
     *
     * @param event Event to persist
     */
    private void writeEvent(final AuditEvent event) {
        try (Connection conn = this.source.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT)) {
            if (event.timestamp() == null) {
                stmt.setNull(1, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                stmt.setTimestamp(1, Timestamp.from(event.timestamp()));
            }
            stmt.setString(2, event.actor());
            stmt.setString(3, event.action());
            // resource_type is a pre-existing column from V100 with a
            // NOT NULL constraint. AuditEvent doesn't carry a separate
            // resource-type concept — the action verb encodes it as
            // its first `_`-separated segment (COOLDOWN_UNBLOCK,
            // REPO_CREATE, SETTINGS_SECTION_UPDATE, …). Derive the
            // category from there so the existing
            // idx_audit_log_resource (resource_type, resource_name)
            // index stays useful for category-scoped queries while
            // every row remains insertable.
            stmt.setString(4, resourceTypeFrom(event.action()));
            stmt.setString(5, event.target());
            stmt.setString(6, renderJson(event.details()));
            stmt.setBoolean(7, event.success());
            if (event.ipAddress() == null) {
                stmt.setNull(8, Types.VARCHAR);
            } else {
                stmt.setString(8, event.ipAddress());
            }
            stmt.executeUpdate();
        } catch (final SQLException ex) {
            throw new AuditPersistenceException(ex);
        }
    }

    /**
     * Derive the {@code resource_type} category from an action verb.
     * The convention is "&lt;CATEGORY&gt;_&lt;VERB&gt;" — for example,
     * {@code COOLDOWN_UNBLOCK} → {@code "cooldown"},
     * {@code REPOSITORY_ACCESS_POLICY_UPDATE} → {@code "repository"},
     * {@code SETTINGS_SECTION_UPDATE} → {@code "settings"}. Lowercased
     * so the V100 {@code idx_audit_log_resource} index has consistent
     * keys. Falls back to {@code "system"} when the action is null,
     * empty, or contains no underscore.
     *
     * <p>Length-capped at 50 chars to match the {@code VARCHAR(50)}
     * column definition; truncates rather than rejecting so an
     * unconventional caller never breaks audit persistence.
     *
     * @param action The audit action verb.
     * @return Non-null lowercase category, max 50 chars.
     */
    static String resourceTypeFrom(final String action) {
        if (action == null || action.isEmpty()) {
            return "system";
        }
        final int sep = action.indexOf('_');
        final String head = sep < 0 ? action : action.substring(0, sep);
        if (head.isEmpty()) {
            return "system";
        }
        final String lower = head.toLowerCase(java.util.Locale.ROOT);
        return lower.length() <= 50 ? lower : lower.substring(0, 50);
    }

    /**
     * Minimal JSON encoder for the {@code details} map. Handles strings,
     * numbers, booleans, and nested maps. Other types are rendered via
     * {@code String.valueOf()} and quoted as strings. We avoid pulling in
     * Jackson here to keep the {@code pantera-main} dependency surface
     * unchanged.
     *
     * @param details Details map (never {@code null} due to record ctor)
     * @return Compact JSON string
     */
    private static String renderJson(final Map<String, Object> details) {
        if (details.isEmpty()) {
            return "{}";
        }
        final StringBuilder out = new StringBuilder();
        out.append('{');
        boolean first = true;
        for (final Map.Entry<String, Object> entry : details.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendQuoted(out, entry.getKey());
            out.append(':');
            appendValue(out, entry.getValue());
        }
        out.append('}');
        return out.toString();
    }

    /**
     * Append a JSON value of unknown type.
     *
     * @param out Destination
     * @param value Value
     */
    private static void appendValue(final StringBuilder out, final Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Boolean) {
            out.append(value);
            return;
        }
        if (value instanceof Number) {
            out.append(value);
            return;
        }
        appendQuoted(out, String.valueOf(value));
    }

    /**
     * Append a quoted, escaped JSON string.
     *
     * @param out Destination
     * @param value Raw string
     */
    private static void appendQuoted(final StringBuilder out, final String value) {
        out.append('"');
        for (int idx = 0; idx < value.length(); idx++) {
            final char chr = value.charAt(idx);
            switch (chr) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (chr < 0x20) {
                        out.append(String.format("\\u%04x", (int) chr));
                    } else {
                        out.append(chr);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Internal wrapper used to surface SQL failures via the async future.
     * Kept package-private so the test can {@code instanceof}-check it.
     */
    static final class AuditPersistenceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AuditPersistenceException(final Throwable cause) {
            super("audit_log write failed", cause);
        }
    }
}
