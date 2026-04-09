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
package com.auto1.pantera.auth;

/**
 * Per-request gate that answers whether a user is still enabled.
 *
 * <p>Used by {@link UnifiedJwtAuthHandler} to reject requests whose
 * token is technically valid (signature, expiry, JTI, not blocklisted)
 * but whose subject has been disabled in Pantera since the token was
 * issued. Without this check, a user disabled via the admin UI would
 * keep full access until their access token expires, which for a
 * long-lived refresh or API token could be days or forever.</p>
 *
 * <p>Implementations are expected to cache aggressively; this method
 * is called on every authenticated request.</p>
 *
 * @since 2.1.0
 */
@FunctionalInterface
public interface UserEnabledCheck {

    /**
     * A no-op implementation that always returns {@code true}. Used
     * when no check is configured (e.g. in tests or legacy deployments
     * without a {@code CachedDbPolicy}).
     */
    UserEnabledCheck ALWAYS_ENABLED = username -> true;

    /**
     * @param username The username (JWT {@code sub} claim)
     * @return {@code true} if the user exists and is enabled, {@code false}
     *     otherwise. Implementations must fail closed on errors.
     */
    boolean isEnabled(String username);
}
