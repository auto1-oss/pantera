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

/**
 * Closed enumeration of audit events — Tier-5 of the observability model
 * (§4.1 / §10.4 of {@code docs/analysis/v2.2-target-architecture.md}).
 *
 * <p>Only four actions qualify as audit events. Anything else (cache writes,
 * negative-cache invalidations, pool initialisations, queue drops, circuit
 * state transitions, ...) is <em>operational</em> and belongs in
 * {@code LocalLogger} (Tier-4), not here. This deliberate smallness keeps the
 * audit dataset compact (90-day retention, compliance-facing) and protects it
 * from "action.type" explosion as new operational events are added.
 *
 * @since 2.2.0
 */
public enum AuditAction {

    /** Upload / deploy / push of an artifact (HTTP {@code PUT}). */
    ARTIFACT_PUBLISH,

    /** Successful serve of an artifact to a client (HTTP {@code GET} 2xx). */
    ARTIFACT_DOWNLOAD,

    /** Explicit delete of an artifact via API or admin action. */
    ARTIFACT_DELETE,

    /**
     * Metadata / index lookup that resolved a concrete coordinate.
     * Emitted when an adapter resolves a client request to a specific
     * {@code (package.name, package.version)} pair.
     */
    RESOLUTION
}
