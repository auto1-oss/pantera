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
package com.auto1.pantera.circuit;

import com.auto1.pantera.test.InMemoryCacheBroadcast;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Two-instance unit test for the breaker/bulkhead settings-loader broadcast
 * (WS2.3, 2.3.0): {@code AdminAuthHandler} publishes on
 * {@link CircuitBreakerSettingsLoader#BROADCAST_CHANNEL} /
 * {@link UpstreamBreakerSettingsLoader#BROADCAST_CHANNEL} after a
 * successful settings PUT; {@code VertxMain} subscribes once per node and
 * calls the installed loader's {@code invalidate()} on receipt.
 * <p>
 * Exercises the exact {@code CacheBroadcast} channel-name contract both
 * sides share, using {@link InMemoryCacheBroadcast} to simulate "node A
 * (admin PUT) / node B (peer)" without a real static-singleton loader —
 * {@code CircuitBreakerSettingsLoader}/{@code UpstreamBreakerSettingsLoader}
 * are process-wide singletons, so a genuinely independent "second node"
 * loader can't exist in one JVM; the counting subscriber here stands in for
 * {@code loader::invalidate} exactly as {@code VertxMain} wires it.
 *
 * @since 2.3.0
 */
final class BreakerSettingsBroadcastWiringTest {

    @Test
    void circuitBreakerSettingsChangePropagatesToPeer() {
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final InMemoryCacheBroadcast nodeAAdmin = new InMemoryCacheBroadcast(bus);
        final InMemoryCacheBroadcast nodeBPeer = new InMemoryCacheBroadcast(bus);
        final AtomicInteger peerInvalidations = new AtomicInteger();
        nodeBPeer.register(
            CircuitBreakerSettingsLoader.BROADCAST_CHANNEL,
            new com.auto1.pantera.asto.misc.Cleanable<>() {
                @Override
                public void invalidate(final String key) {
                    peerInvalidations.incrementAndGet();
                }

                @Override
                public void invalidateAll() {
                    peerInvalidations.incrementAndGet();
                }
            }
        );
        // Exactly what AdminAuthHandler#updateCircuitBreakerSettings does
        // after a successful DB write.
        nodeAAdmin.publish(CircuitBreakerSettingsLoader.BROADCAST_CHANNEL, "changed");
        MatcherAssert.assertThat(
            "A settings change on node A (admin PUT) must invalidate the "
                + "peer's loader — pre-2.3.0 loader.invalidate() only ever "
                + "affected the receiving node",
            peerInvalidations.get(),
            Matchers.is(1)
        );
    }

    @Test
    void upstreamBreakerSettingsChangePropagatesToPeer() {
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final InMemoryCacheBroadcast nodeAAdmin = new InMemoryCacheBroadcast(bus);
        final InMemoryCacheBroadcast nodeBPeer = new InMemoryCacheBroadcast(bus);
        final AtomicInteger peerInvalidations = new AtomicInteger();
        nodeBPeer.register(
            UpstreamBreakerSettingsLoader.BROADCAST_CHANNEL,
            new com.auto1.pantera.asto.misc.Cleanable<>() {
                @Override
                public void invalidate(final String key) {
                    peerInvalidations.incrementAndGet();
                }

                @Override
                public void invalidateAll() {
                    peerInvalidations.incrementAndGet();
                }
            }
        );
        nodeAAdmin.publish(UpstreamBreakerSettingsLoader.BROADCAST_CHANNEL, "changed");
        MatcherAssert.assertThat(peerInvalidations.get(), Matchers.is(1));
    }

    @Test
    void theTwoBreakerChannelsAreDistinctAndDoNotCrossFire() {
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final InMemoryCacheBroadcast nodeAAdmin = new InMemoryCacheBroadcast(bus);
        final InMemoryCacheBroadcast nodeBPeer = new InMemoryCacheBroadcast(bus);
        final AtomicInteger circuitBreakerHits = new AtomicInteger();
        final AtomicInteger upstreamBreakerHits = new AtomicInteger();
        nodeBPeer.register(
            CircuitBreakerSettingsLoader.BROADCAST_CHANNEL,
            new com.auto1.pantera.asto.misc.Cleanable<>() {
                @Override
                public void invalidate(final String key) {
                    circuitBreakerHits.incrementAndGet();
                }

                @Override
                public void invalidateAll() {
                    circuitBreakerHits.incrementAndGet();
                }
            }
        );
        nodeBPeer.register(
            UpstreamBreakerSettingsLoader.BROADCAST_CHANNEL,
            new com.auto1.pantera.asto.misc.Cleanable<>() {
                @Override
                public void invalidate(final String key) {
                    upstreamBreakerHits.incrementAndGet();
                }

                @Override
                public void invalidateAll() {
                    upstreamBreakerHits.incrementAndGet();
                }
            }
        );
        nodeAAdmin.publish(UpstreamBreakerSettingsLoader.BROADCAST_CHANNEL, "changed");
        MatcherAssert.assertThat(
            "Only the upstream-breaker subscriber must fire",
            upstreamBreakerHits.get(),
            Matchers.is(1)
        );
        MatcherAssert.assertThat(
            "The distinct group-member breaker channel must not cross-fire",
            circuitBreakerHits.get(),
            Matchers.is(0)
        );
    }
}
