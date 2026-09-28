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

import io.vertx.core.Vertx;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Behavioural tests for {@link RepositoryEventBroadcaster}: the fix for
 * HA peers keeping stale repository security settings.
 *
 * <p>Before 2.2.9 a repository upsert/remove/move was published on the
 * LOCAL (non-clustered) Vert.x event bus only, while the config itself is
 * persisted in the cluster-shared database — so every other node kept its
 * {@code DbRepositories} snapshot (and the anonymous-access gate baked
 * into its cached slices) until restart. The broadcaster publishes the
 * same event to peers over the Valkey pub/sub, and a peer re-injects a
 * received event onto ITS local bus so the existing consumer reloads —
 * without re-broadcasting (that would ping-pong between peers).</p>
 *
 * @since 2.2.9
 */
final class RepositoryEventBroadcasterTest {

    private Vertx vertx;

    @BeforeEach
    void up() {
        this.vertx = Vertx.vertx();
    }

    @AfterEach
    void down() {
        this.vertx.close();
    }

    @Test
    @Timeout(10)
    void publishReachesLocalBusAndPeers() throws Exception {
        final List<String> remote = new CopyOnWriteArrayList<>();
        final List<String> local = new CopyOnWriteArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        this.vertx.eventBus().consumer(RepositoryEvents.ADDRESS, msg -> {
            local.add(String.valueOf(msg.body()));
            delivered.countDown();
        });
        final RepositoryEventBroadcaster broadcaster =
            new RepositoryEventBroadcaster(this.vertx.eventBus(), remote::add);
        broadcaster.publish(RepositoryEvents.upsert("secured-repo"));
        MatcherAssert.assertThat(
            "the local consumer must receive the event",
            delivered.await(5, TimeUnit.SECONDS), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the same event must be broadcast to peers",
            remote, new IsEqual<>(List.of("UPSERT|secured-repo"))
        );
    }

    @Test
    @Timeout(10)
    void receivedPeerEventIsReinjectedLocallyWithoutEcho() throws Exception {
        final List<String> remote = new CopyOnWriteArrayList<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        final List<String> local = new CopyOnWriteArrayList<>();
        this.vertx.eventBus().consumer(RepositoryEvents.ADDRESS, msg -> {
            local.add(String.valueOf(msg.body()));
            delivered.countDown();
        });
        final RepositoryEventBroadcaster broadcaster =
            new RepositoryEventBroadcaster(this.vertx.eventBus(), remote::add);
        broadcaster.receive(RepositoryEvents.remove("gone-repo"));
        MatcherAssert.assertThat(
            "a peer's event must drive this node's local consumer (reload)",
            delivered.await(5, TimeUnit.SECONDS), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the re-injected event must be the peer's event",
            local, new IsEqual<>(List.of("REMOVE|gone-repo"))
        );
        MatcherAssert.assertThat(
            "a received event must NOT be re-broadcast (no peer ping-pong)",
            remote.isEmpty(), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(10)
    void singleNodeWithoutPubSubStaysLocal() throws Exception {
        final CountDownLatch delivered = new CountDownLatch(1);
        this.vertx.eventBus().consumer(RepositoryEvents.ADDRESS, msg -> delivered.countDown());
        final RepositoryEventBroadcaster broadcaster =
            new RepositoryEventBroadcaster(this.vertx.eventBus(), null);
        broadcaster.publish(RepositoryEvents.upsert("solo"));
        MatcherAssert.assertThat(
            "without pub/sub the local path must still work",
            delivered.await(5, TimeUnit.SECONDS), new IsEqual<>(true)
        );
    }
}
