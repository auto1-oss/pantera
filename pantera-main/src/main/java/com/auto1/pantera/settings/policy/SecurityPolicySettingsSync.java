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
package com.auto1.pantera.settings.policy;

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;

/**
 * Cross-node propagation of security-policy settings writes: the node that
 * served the admin {@code PUT} reloads its own loader and publishes the
 * section name on the shared cache-invalidation channel; every peer
 * reloads the same loader on receipt (the channel filters a node's own
 * messages). Without Valkey there are no peers and this is local-only.
 *
 * @since 2.2.9
 */
public final class SecurityPolicySettingsSync {

    /**
     * Channel namespace.
     */
    static final String NAMESPACE = "security_policy_settings";

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.settings.policy";

    /**
     * Cross-node bus, or null on a single node.
     */
    private final CacheInvalidationPubSub bus;

    /**
     * Ctor.
     * @param bus Cross-node bus, nullable
     */
    private SecurityPolicySettingsSync(final CacheInvalidationPubSub bus) {
        this.bus = bus;
    }

    /**
     * Subscribe to peers' writes and return a publisher for this node's.
     * @param bus Cross-node bus when Valkey is configured
     * @return Sync
     */
    public static SecurityPolicySettingsSync attach(final Optional<CacheInvalidationPubSub> bus) {
        final SecurityPolicySettingsSync sync = new SecurityPolicySettingsSync(bus.orElse(null));
        bus.ifPresent(present -> present.subscribe(NAMESPACE, sync::receive));
        return sync;
    }

    /**
     * Tell peers a section was written.
     * @param section Section name ({@code request_limits}, {@code egress}, {@code login_throttle})
     */
    public void broadcast(final String section) {
        if (this.bus != null) {
            this.bus.publish(NAMESPACE, section);
        }
    }

    /**
     * A peer wrote a section: reload the matching loader here.
     * @param section Section name, or {@code *} for all
     */
    void receive(final String section) {
        final boolean all = "*".equals(section);
        if (all || "request_limits".equals(section)) {
            invalidate(RequestLimitsSettingsLoader.installed());
        }
        if (all || "egress".equals(section)) {
            invalidate(EgressSettingsLoader.installed());
        }
        if (all || "login_throttle".equals(section)) {
            invalidate(LoginThrottleSettingsLoader.installed());
        }
        EcsLogger.info(LOGGER)
            .message("Reloaded " + section + " settings after a peer node's update")
            .eventCategory("configuration")
            .eventAction("security_policy_settings_reload")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    private static void invalidate(final RequestLimitsSettingsLoader loader) {
        if (loader != null) {
            loader.invalidate();
        }
    }

    private static void invalidate(final EgressSettingsLoader loader) {
        if (loader != null) {
            loader.invalidate();
        }
    }

    private static void invalidate(final LoginThrottleSettingsLoader loader) {
        if (loader != null) {
            loader.invalidate();
        }
    }
}
