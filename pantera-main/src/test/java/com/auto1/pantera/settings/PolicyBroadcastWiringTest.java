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
package com.auto1.pantera.settings;

import com.auto1.pantera.asto.misc.Cleanable;
import com.auto1.pantera.cache.PublishingCleanable;
import com.auto1.pantera.test.InMemoryCacheBroadcast;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * Two-instance unit test proving the exact policy-cache pub/sub wiring
 * {@code YamlSettings} performs (WS2.3, 2.3.0): the raw policy cache
 * registered as the pub/sub <em>receiver</em>, wrapped in
 * {@link PublishingCleanable} as the <em>publisher</em> exposed via
 * {@code PanteraCaches#policyCache()} — the same accessor
 * {@code RoleHandler}/{@code UserHandler} call {@code invalidate(...)} on
 * for every role/permission and user mutation.
 * <p>
 * Pre-2.3.0 the policy cache had a receiver but no publisher at all
 * ({@code PanteraCaches.All} derived a fresh, unwrapped {@code Cleanable} by
 * {@code instanceof}-checking the raw policy on every
 * {@code policyCache()} call) — a role/permission change on one node never
 * reached any peer.
 *
 * @since 2.3.0
 */
final class PolicyBroadcastWiringTest {

    @Test
    void roleInvalidationOnOneNodePropagatesToThePeersRawPolicyCache() {
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final InMemoryCacheBroadcast pubSubA = new InMemoryCacheBroadcast(bus);
        final InMemoryCacheBroadcast pubSubB = new InMemoryCacheBroadcast(bus);
        final CountingPolicyCache rawA = new CountingPolicyCache();
        final CountingPolicyCache rawB = new CountingPolicyCache();
        // Exactly YamlSettings' wiring: raw cache registered as receiver,
        // PublishingCleanable-wrapped raw cache as the exposed accessor.
        pubSubA.register("policy", rawA);
        pubSubB.register("policy", rawB);
        final Cleanable<String> policyCacheA = new PublishingCleanable(rawA, pubSubA, "policy");
        // Simulates RoleHandler#putRole: this.policyCache.invalidate(rname)
        policyCacheA.invalidate("role-x");
        MatcherAssert.assertThat(
            "The local (node A) raw policy cache must be invalidated",
            rawA.invalidations.get(),
            Matchers.is(1)
        );
        MatcherAssert.assertThat(
            "The peer's (node B) raw policy cache must ALSO be invalidated "
                + "— this is exactly the propagation pre-2.3.0 never happened",
            rawB.invalidations.get(),
            Matchers.is(1)
        );
        MatcherAssert.assertThat(
            "The peer must receive the same key that was invalidated locally",
            rawB.lastKey,
            Matchers.is("role-x")
        );
    }

    @Test
    void userDisableInvalidationPropagatesToo() {
        // UserHandler invalidates by username on enable/disable/update/
        // delete — same accessor, same wiring, different key shape.
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final InMemoryCacheBroadcast pubSubA = new InMemoryCacheBroadcast(bus);
        final InMemoryCacheBroadcast pubSubB = new InMemoryCacheBroadcast(bus);
        final CountingPolicyCache rawA = new CountingPolicyCache();
        final CountingPolicyCache rawB = new CountingPolicyCache();
        pubSubA.register("policy", rawA);
        pubSubB.register("policy", rawB);
        final Cleanable<String> policyCacheA = new PublishingCleanable(rawA, pubSubA, "policy");
        policyCacheA.invalidate("alice");
        MatcherAssert.assertThat(rawB.invalidations.get(), Matchers.is(1));
        MatcherAssert.assertThat(rawB.lastKey, Matchers.is("alice"));
    }

    /**
     * Minimal invocation-counting {@link Cleanable} standing in for
     * {@code CachedDbPolicy}/{@code CachedYamlPolicy}.
     * @since 2.3.0
     */
    private static final class CountingPolicyCache implements Cleanable<String> {

        /**
         * Number of {@link #invalidate(String)}/{@link #invalidateAll()} calls.
         */
        private final AtomicInteger invalidations = new AtomicInteger();

        /**
         * Last key passed to {@link #invalidate(String)}.
         */
        private volatile String lastKey;

        @Override
        public void invalidate(final String key) {
            this.lastKey = key;
            this.invalidations.incrementAndGet();
        }

        @Override
        public void invalidateAll() {
            this.invalidations.incrementAndGet();
        }
    }
}
