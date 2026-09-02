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

import com.auto1.pantera.cache.GlobalCacheConfig;
import com.auto1.pantera.http.log.EcsLogger;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Pending SSO logins keyed by OAuth {@code state}: issue a nonce when the
 * authorize redirect is built, consume it exactly once when the callback
 * arrives. Cluster-shared over Valkey when configured (so the callback may
 * land on any node), in-memory otherwise. Every operation is asynchronous
 * so callers on the event loop never block.
 *
 * @since 2.2.9
 */
public interface SsoLoginStateStore {

    /**
     * Fresh, unguessable OAuth state.
     * @return URL-safe random string
     */
    String newState();

    /**
     * Bind a fresh nonce to a state for the store's lifetime.
     * @param state OAuth state
     * @return The nonce, once durably recorded
     */
    CompletionStage<String> issue(String state);

    /**
     * Take the nonce bound to a state; a second call, an unknown state or
     * an expired one yields empty.
     * @param state OAuth state, may be null
     * @return Nonce, or empty
     */
    CompletionStage<Optional<String>> consume(String state);

    /**
     * The store for this process: Valkey-backed when a global Valkey
     * connection is configured, in-memory otherwise. The choice is logged
     * because it decides whether SSO logins survive a node switch.
     * @param ttl Lifetime of a pending login
     * @return Store
     */
    static SsoLoginStateStore forRuntime(final Duration ttl) {
        final Optional<SsoLoginStateStore> shared = GlobalCacheConfig.valkeyConnection()
            .map(valkey -> new ValkeySsoLoginStateStore(valkey, ttl));
        EcsLogger.info("com.auto1.pantera.auth.oidc")
            .message(shared.isPresent()
                ? "SSO login state store: Valkey (cluster-shared, callback may land on any node)"
                : "SSO login state store: in-memory (single node)")
            .eventCategory("configuration")
            .eventAction("sso_state_store_select")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
        return shared.orElseGet(() -> new InMemorySsoLoginStateStore(new SsoNonceStore(ttl)));
    }
}
