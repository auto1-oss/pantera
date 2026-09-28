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
package com.auto1.pantera.api;

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.http.log.EcsLogger;
import io.vertx.core.eventbus.EventBus;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Publishes repository lifecycle events ({@link RepositoryEvents}) to the
 * local Vert.x event bus AND to every peer node.
 *
 * <p>Pantera's Vert.x is deliberately non-clustered, so before 2.2.9 a
 * repository upsert/remove/move — including a security-tightening change
 * such as revoking anonymous reads — reached only the node that handled
 * the admin request; peers kept their {@code DbRepositories} snapshot and
 * the anonymous-access gate baked into their cached slices until restart,
 * while the HA docs promised propagation. This broadcaster mirrors every
 * event onto the shared Valkey {@link CacheInvalidationPubSub} under the
 * {@value #NAMESPACE} namespace; a peer re-injects a received event onto
 * its own local bus, where the existing consumer in {@code VertxMain}
 * reloads and invalidates exactly as for a local change.</p>
 *
 * <p>Re-injection never re-broadcasts: the pub/sub already filters a
 * node's own messages by instance id, and forwarding a received event
 * again would ping-pong between peers.</p>
 *
 * @since 2.2.9
 */
public final class RepositoryEventBroadcaster {

    /**
     * Pub/sub namespace for repository lifecycle events.
     */
    public static final String NAMESPACE = "repo-config";

    /**
     * Local event bus.
     */
    private final EventBus local;

    /**
     * Cross-node publisher; {@code null} on a single node.
     */
    private final Consumer<String> remote;

    /**
     * Ctor.
     *
     * @param local Local event bus
     * @param remote Cross-node publisher (nullable)
     */
    public RepositoryEventBroadcaster(final EventBus local, final Consumer<String> remote) {
        this.local = local;
        this.remote = remote;
    }

    /**
     * Broadcaster wired to the deployment's pub/sub, subscribed for peers'
     * events. Single-node (no pub/sub) → local only.
     *
     * @param local Local event bus
     * @param bus Cross-node pub/sub when configured
     * @return Broadcaster
     */
    public static RepositoryEventBroadcaster attach(
        final EventBus local, final Optional<CacheInvalidationPubSub> bus
    ) {
        final RepositoryEventBroadcaster result = new RepositoryEventBroadcaster(
            local,
            bus.<Consumer<String>>map(pubsub -> event -> pubsub.publish(NAMESPACE, event))
                .orElse(null)
        );
        bus.ifPresent(pubsub -> {
            pubsub.subscribe(NAMESPACE, result::receive);
            EcsLogger.info("com.auto1.pantera.api")
                .message("Repository lifecycle events fan out to peers over pub/sub")
                .eventCategory("configuration")
                .eventAction("repo_events_pubsub_wire")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
        });
        return result;
    }

    /**
     * Publish a local change: local bus plus every peer.
     *
     * @param event Event string from {@link RepositoryEvents}
     */
    public void publish(final String event) {
        this.local.publish(RepositoryEvents.ADDRESS, event);
        if (this.remote != null) {
            try {
                this.remote.accept(event);
            } catch (final RuntimeException ex) {
                EcsLogger.warn("com.auto1.pantera.api")
                    .message("Repository event broadcast to peers failed; peers converge on restart")
                    .eventCategory("configuration")
                    .eventAction("repo_events_publish")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    /**
     * A peer's event: re-inject on the local bus only.
     *
     * @param event Event string as published by the peer
     */
    void receive(final String event) {
        EcsLogger.info("com.auto1.pantera.api")
            .message("Applying repository event received from a peer node")
            .eventCategory("configuration")
            .eventAction("repo_events_receive")
            .eventOutcome("success")
            .field("event.reason", event)
            .field("log.source", "application")
            .log();
        this.local.publish(RepositoryEvents.ADDRESS, event);
    }
}
