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
package com.auto1.pantera.auth.oidc;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The in-memory SSO login store shared by the whole process.
 *
 * <p>The API verticle is deployed as several instances, each with its own
 * {@code AuthHandler}. Without Valkey, a store per handler meant the IdP
 * callback — a new connection, balanced to any instance — usually missed
 * the state its {@code /redirect} had recorded on another instance, and the
 * login failed as "invalid or expired". One store per process (per TTL)
 * fixes that; cross-node sharing still needs Valkey.</p>
 *
 * @since 2.2.9
 */
final class ProcessLocalSsoLogins {

    /**
     * Stores by pending-login lifetime.
     */
    private static final ConcurrentMap<Duration, SsoLoginStateStore> STORES =
        new ConcurrentHashMap<>();

    /**
     * Not instantiable.
     */
    private ProcessLocalSsoLogins() {
    }

    /**
     * The process-wide in-memory store for a TTL.
     * @param ttl Lifetime of a pending login
     * @return Store shared by every caller in this JVM
     */
    static SsoLoginStateStore store(final Duration ttl) {
        return STORES.computeIfAbsent(
            ttl, lifetime -> new InMemorySsoLoginStateStore(new SsoNonceStore(lifetime))
        );
    }
}
