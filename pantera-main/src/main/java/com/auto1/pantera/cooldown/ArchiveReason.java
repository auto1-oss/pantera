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
package com.auto1.pantera.cooldown;

/**
 * Reason a cooldown block was moved from {@code artifact_cooldowns} to
 * {@code artifact_cooldowns_history}.
 */
public enum ArchiveReason {
    /**
     * Cron or Vertx fallback auto-cleanup of an expired cooldown.
     */
    EXPIRED,
    /**
     * Admin unblock action.
     */
    MANUAL_UNBLOCK,
    /**
     * Reserved for future bulk purge action.
     */
    ADMIN_PURGE
}
