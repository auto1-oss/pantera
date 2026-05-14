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

import java.util.concurrent.CompletableFuture;

/**
 * Persistence interface for {@link AuditEvent} records — the T-S04 hook.
 *
 * <p>Implementations are responsible for durability semantics: JDBC-backed
 * impls write synchronously through the connection pool; an in-memory
 * test impl can simply accumulate. The contract is that
 * {@link #record(AuditEvent)} never blocks the caller — the returned
 * future reflects the persistence outcome.
 *
 * <p>Implementations MUST swallow persistence failures internally and
 * return a completed-exceptionally future rather than throwing from
 * {@link #record(AuditEvent)}. Audit recording is non-critical for the
 * user-facing operation; a failed audit write must not break the admin
 * action.
 *
 * @since 2.2.0
 */
public interface AuditService {

    /**
     * Persist an audit event. The returned future completes when the event
     * is durable; on failure the future completes exceptionally but the
     * caller's mutation is unaffected (audit failures are logged at WARN
     * level — see the JDBC impl).
     *
     * @param event Event to persist; never {@code null}
     * @return Future completing on persistence outcome
     */
    CompletableFuture<Void> record(AuditEvent event);

    /**
     * No-op service for tests / disabled-audit configurations. Always
     * returns a completed future without persisting.
     *
     * @return A no-op {@link AuditService}
     */
    static AuditService noop() {
        return event -> CompletableFuture.completedFuture(null);
    }
}
