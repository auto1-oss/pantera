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
 * Process-wide accessor for the shared {@link AuditService} — the T-S04
 * wiring hook used by admin handlers.
 *
 * <p>Mirrors {@code CacheWriteCallbackRegistry}: VertxMain installs the
 * concrete {@code JdbcAuditService} at boot via
 * {@link #setSharedService(AuditService)}; admin handlers retrieve it
 * via {@link #sharedService()} without taking the dependency on the
 * concrete impl module.
 *
 * <p>The registry returns a no-op service when no implementation has been
 * installed (tests, DB-less boot) so callers never null-check.
 *
 * <p>Thread-safety: the shared reference is volatile; reads are lock-free.
 *
 * @since 2.2.0
 */
public final class AuditServiceRegistry {

    /** Singleton instance. */
    private static final AuditServiceRegistry INSTANCE = new AuditServiceRegistry();

    /** No-op fallback. */
    private static final AuditService NO_OP = AuditService.noop();

    /** Currently installed service; {@code null} when none registered. */
    private volatile AuditService shared;

    /** Private — singleton. */
    private AuditServiceRegistry() {
        // singleton
    }

    /**
     * Process-wide singleton accessor.
     *
     * @return The registry instance
     */
    public static AuditServiceRegistry instance() {
        return INSTANCE;
    }

    /**
     * Install the shared audit service. Called once at VertxMain boot.
     *
     * @param service Concrete service (e.g. {@code JdbcAuditService})
     */
    public void setSharedService(final AuditService service) {
        this.shared = service;
    }

    /**
     * Whether a concrete service has been registered.
     *
     * @return {@code true} when a service is installed
     */
    public boolean isSharedServiceSet() {
        return this.shared != null;
    }

    /**
     * Retrieve the shared service — never {@code null}.
     *
     * @return Installed service when present; no-op fallback otherwise
     */
    public AuditService sharedService() {
        final AuditService snap = this.shared;
        if (snap != null) {
            return snap;
        }
        return NO_OP;
    }

    /** Clear the shared reference — used by tests. */
    public void clear() {
        this.shared = null;
    }
}
