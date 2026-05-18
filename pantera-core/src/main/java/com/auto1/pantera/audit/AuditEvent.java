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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Structured admin-action audit event — the T-S04 record.
 *
 * <p>Distinct from {@link AuditLogger} which emits operational audit log
 * lines (artifact publish/download/delete/resolution). {@link AuditEvent}
 * captures administrative mutations: cooldown unblocks, cache clears,
 * repo create/delete, user/role changes, PGP keyring changes, etc.
 *
 * <p>Industry standard: SOC2 / ISO 27001 require an immutable trail of
 * admin actions with actor, target, outcome, and source IP. The
 * persistence side enforces immutability via PostgreSQL {@code BEFORE
 * UPDATE} / {@code BEFORE DELETE} triggers — see migration
 * {@code V129__audit_log_insert_only.sql}.
 *
 * @param timestamp When the action was attempted (UTC). May be {@code null};
 *                  the persistence layer will default to {@code NOW()}.
 * @param actor Authenticated principal that performed the action (e.g.
 *              {@code "alice@example.com"}). Never {@code null}.
 * @param action Action verb in {@code SCREAMING_SNAKE_CASE}. Examples:
 *               {@code "COOLDOWN_UNBLOCK"}, {@code "CACHE_CLEAR"},
 *               {@code "REPO_CREATE"}, {@code "REPO_DELETE"}. Never empty.
 * @param target Target identifier (repo name, user name, artifact path, etc.).
 *               May be {@code null} for actions without a specific target.
 * @param details Free-form structured payload — serialised as JSON in the
 *                {@code details} column. {@code null} or empty map →
 *                empty JSON object.
 * @param success {@code true} for completed mutations, {@code false} for
 *                rejected ones (401 / 403 / validation errors).
 * @param ipAddress Source IP from {@code X-Forwarded-For} / {@code X-Real-IP}
 *                  / remote address. May be {@code null} for system-initiated
 *                  actions.
 * @param oldValueJson Snapshot of the affected resource <em>before</em> the
 *                     mutation, encoded as a JSON literal. {@code null} when
 *                     the action has no meaningful pre-image (e.g. unblock,
 *                     cache clear) or the prior value could not be read.
 *                     Persisted into the V100 {@code old_value} JSONB column.
 * @param newValueJson Snapshot of the affected resource <em>after</em> the
 *                     mutation, encoded as a JSON literal. {@code null} for
 *                     delete-style actions or when no post-image is captured.
 *                     Persisted into the V100 {@code new_value} JSONB column.
 *
 * @since 2.2.0
 */
public record AuditEvent(
    Instant timestamp,
    String actor,
    String action,
    String target,
    Map<String, Object> details,
    boolean success,
    String ipAddress,
    String oldValueJson,
    String newValueJson
) {

    /**
     * Compact constructor — validates required fields and defensively
     * copies the {@code details} map.
     *
     * @throws NullPointerException when {@code actor} or {@code action} is null
     * @throws IllegalArgumentException when {@code action} is empty
     */
    public AuditEvent {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        if (action.isEmpty()) {
            throw new IllegalArgumentException("action must not be empty");
        }
        details = defensiveCopy(details);
    }

    /**
     * Back-compat constructor (no diff). Most callers that have not yet
     * been migrated to capture old/new value snapshots continue to use
     * this seven-arg shape; both diff fields default to {@code null}.
     *
     * @param timestamp See record javadoc
     * @param actor See record javadoc
     * @param action See record javadoc
     * @param target See record javadoc
     * @param details See record javadoc
     * @param success See record javadoc
     * @param ipAddress See record javadoc
     */
    public AuditEvent(
        final Instant timestamp,
        final String actor,
        final String action,
        final String target,
        final Map<String, Object> details,
        final boolean success,
        final String ipAddress
    ) {
        this(timestamp, actor, action, target, details, success, ipAddress, null, null);
    }

    /**
     * Defensive copy for the details map. Returns an immutable copy when
     * the input is non-null, an empty map otherwise.
     *
     * @param details Caller-supplied map (nullable)
     * @return Immutable copy or empty map
     */
    private static Map<String, Object> defensiveCopy(
        final Map<String, Object> details
    ) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(details);
    }

    /**
     * Builder-style helper for the common case: success event with no details.
     *
     * @param actor Authenticated principal
     * @param action Action verb
     * @param target Target identifier (nullable)
     * @return Successful event with empty details map
     */
    public static AuditEvent success(
        final String actor, final String action, final String target
    ) {
        return new AuditEvent(
            Instant.now(), actor, action, target, Map.of(), true, null, null, null
        );
    }

    /**
     * Builder-style helper for failed admin actions.
     *
     * @param actor Authenticated principal
     * @param action Action verb
     * @param target Target identifier (nullable)
     * @param reason Failure reason — written to {@code details.reason}
     * @return Failed event
     */
    public static AuditEvent failure(
        final String actor, final String action, final String target,
        final String reason
    ) {
        return new AuditEvent(
            Instant.now(), actor, action, target,
            Map.of("reason", reason == null ? "unknown" : reason),
            false, null, null, null
        );
    }

    /**
     * Return a new event with the given source IP attached. Used by HTTP
     * handlers that have access to the request's client IP via the trace
     * MDC. Records (and the underlying {@link AuditEvent} contract) are
     * immutable, so this returns a new instance.
     *
     * @param ipAddress Source IP (nullable — pass through unchanged on null)
     * @return New event with the IP set
     */
    public AuditEvent withIpAddress(final String ipAddress) {
        return new AuditEvent(
            this.timestamp, this.actor, this.action, this.target,
            this.details, this.success, ipAddress, this.oldValueJson, this.newValueJson
        );
    }

    /**
     * Return a new event carrying the before/after diff of the affected
     * resource. The strings must be JSON literals — they are inserted into
     * the {@code old_value} / {@code new_value} JSONB columns via a
     * {@code ?::jsonb} cast and must therefore parse as valid JSON.
     *
     * @param oldValueJson JSON literal of the pre-mutation state, or {@code null}
     * @param newValueJson JSON literal of the post-mutation state, or {@code null}
     * @return New event with the diff fields populated
     */
    public AuditEvent withChange(
        final String oldValueJson, final String newValueJson
    ) {
        return new AuditEvent(
            this.timestamp, this.actor, this.action, this.target,
            this.details, this.success, this.ipAddress, oldValueJson, newValueJson
        );
    }
}
