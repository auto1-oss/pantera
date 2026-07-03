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
package com.auto1.pantera.http.observability;

import org.apache.logging.log4j.Level;

/**
 * Table-driven log-level policy for the five observability tiers — implements
 * §4.2 of {@code docs/analysis/v2.2-target-architecture.md}.
 *
 * <p>Every tier has a DEBUG hook for successful operations; WARN / INFO / ERROR
 * map to specific failure modes. Having the mapping as a single enum means a
 * reviewer can audit the full log-level policy in one file — no per-adapter
 * drift, no per-call-site bespoke levels.
 *
 * <p>Usage:
 * <pre>{@code
 *   logger.atLevel(LevelPolicy.CLIENT_FACING_NOT_FOUND.level()).log(...);
 * }</pre>
 *
 * <p>Changing the level of an existing entry is a release-gated decision (it
 * changes log-volume and alert routing). Adding a new entry is a deliberate
 * breaking change because the {@link StructuredLogger} tier builders may
 * {@code switch} over these values.
 *
 * @since 2.2.0
 */
public enum LevelPolicy {

    // ---- Tier-1 — client → pantera (access log) ----

    /** 2xx / 3xx response to client. DEBUG so production logs are quiet. */
    CLIENT_FACING_SUCCESS(Level.DEBUG),

    /** 404 Not Found — normal for metadata probes (Maven HEAD, npm audit, etc.). */
    CLIENT_FACING_NOT_FOUND(Level.INFO),

    /** 401 / 403 — normal for unauthenticated probes / per-client retries. */
    CLIENT_FACING_UNAUTH(Level.INFO),

    /** 400 and other 4xx — genuine client-side misuse. */
    CLIENT_FACING_4XX_OTHER(Level.WARN),

    /** 5xx — server-side problem, always actionable. */
    CLIENT_FACING_5XX(Level.ERROR),

    /** Request exceeded the slow threshold (default 5000 ms). */
    CLIENT_FACING_SLOW(Level.WARN),

    // ---- Tier-2 — pantera → pantera (group → member, slice → slice) ----

    /** 2xx returned by an internal callee. DEBUG — opt-in trace. */
    INTERNAL_CALL_SUCCESS(Level.DEBUG),

    /** 404 from an internal callee (hosted member). DEBUG — normal on fanout. */
    INTERNAL_CALL_NOT_FOUND(Level.DEBUG),

    /**
     * 500 from an internal callee. ERROR with Fault cause, stack trace, and
     * parent {@code trace.id} — this is the primary "internal-chain failed"
     * signal.
     */
    INTERNAL_CALL_500(Level.ERROR),

    // ---- Tier-3 — pantera → upstream (HTTP call to npmjs.org / central / etc) ----

    /** 2xx returned by an upstream remote. DEBUG — opt-in trace. */
    UPSTREAM_SUCCESS(Level.DEBUG),

    /** 404 from an upstream remote. DEBUG — normal during proxy fanouts. */
    UPSTREAM_NOT_FOUND(Level.DEBUG),

    /**
     * 5xx or connection exception from an upstream remote. ERROR with
     * destination + duration + cause.
     */
    UPSTREAM_5XX(Level.ERROR),

    // ---- Tier-4 — local operations (DB, Quartz, Caffeine, Valkey, storage, pool init) ----

    /** Config load, pool init, service start — operator-visible lifecycle events. */
    LOCAL_CONFIG_CHANGE(Level.INFO),

    /** Routine local operation succeeded. DEBUG — enable per-component for investigation. */
    LOCAL_OP_SUCCESS(Level.DEBUG),

    /** Fallback, rate-shed, retry, queue-near-full — system degraded but serving. */
    LOCAL_DEGRADED(Level.WARN),

    /** Local operation failed. ERROR with cause (required). */
    LOCAL_FAILURE(Level.ERROR),

    // ---- Tier-5 — audit (always emitted, routed to the audit dataset) ----

    /**
     * Compliance audit event — {@code ARTIFACT_PUBLISH} / {@code ARTIFACT_DOWNLOAD} /
     * {@code ARTIFACT_DELETE} / {@code RESOLUTION}. INFO level, but the audit logger
     * is configured to NEVER be suppressed regardless of operational log-level settings.
     */
    AUDIT_EVENT(Level.INFO);

    /** Log4j2 level the policy maps to. */
    private final Level level;

    LevelPolicy(final Level assigned) {
        this.level = assigned;
    }

    /**
     * @return the Log4j2 {@link Level} this policy entry maps to. Callers should
     *         use this value with {@code logger.atLevel(level)} or an equivalent
     *         dispatcher switch.
     */
    public Level level() {
        return this.level;
    }
}
