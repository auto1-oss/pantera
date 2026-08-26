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

import com.auto1.pantera.test.InMemoryCacheBroadcast;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Two-instance unit tests for {@link ValkeyRevocationBlocklist} (WS2.1,
 * 2.3.0) proving the acceptance criteria with a Valkey/DB fake — no Docker,
 * no wall-clock latency assertions (state/invocation-count/eventual-
 * convergence only, per project testing doctrine).
 *
 * @since 2.3.0
 */
final class ValkeyRevocationBlocklistPropagationTest {

    @Test
    @Timeout(10)
    void nodeThatBootsAfterARevocationRejectsItImmediately() {
        // "Restart" is simulated by two ValkeyRevocationBlocklist instances
        // sharing one durable store — exactly what a real restart against
        // the same Postgres looks like.
        final InMemoryRevocationStore sharedStore = new InMemoryRevocationStore();
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final ValkeyRevocationBlocklist nodeA = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(bus), sharedStore, 3600
        );
        nodeA.revokeJti("pre-existing", 3600);
        // Construction alone (boot hydration is synchronous and forced —
        // pollSince(EPOCH) — before the constructor returns) must already
        // reflect the revocation; no isRevokedJti call is needed first to
        // trigger it.
        final ValkeyRevocationBlocklist restarted = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(bus), sharedStore, 3600
        );
        MatcherAssert.assertThat(
            "A freshly-booted node sharing the durable store must reject an "
                + "already-revoked, unexpired token immediately — a restart "
                + "must never re-honor a revoked token",
            restarted.isRevokedJti("pre-existing"),
            Matchers.is(true)
        );
    }

    @Test
    @Timeout(10)
    void peerThatMissedThePubSubMessageConvergesOnItsNextPoll() {
        final InMemoryRevocationStore sharedStore = new InMemoryRevocationStore();
        // Small poll interval (test seam) — deterministic convergence via
        // Awaitility polling, not a multi-second sleep.
        final ValkeyRevocationBlocklist isolated = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(new InMemoryCacheBroadcast.Bus()),
            sharedStore, 3600, 20L
        );
        MatcherAssert.assertThat(
            "Nothing revoked yet",
            isolated.isRevokedJti("never-broadcast"),
            Matchers.is(false)
        );
        // Simulate "another node revoked it and the DB row landed, but the
        // pub/sub message never reached this instance" — write straight to
        // the shared store, bypassing revokeJti()/publish() entirely.
        sharedStore.insert("jti", "never-broadcast", 3600);
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .until(() -> isolated.isRevokedJti("never-broadcast"));
    }

    @Test
    @Timeout(10)
    void peerAppliesTheRevokedTokensRealRemainingTtlNotItsOwnConfiguredDefault() {
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final ValkeyRevocationBlocklist nodeA = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(bus), new InMemoryRevocationStore(), 100
        );
        // Node B's own configured default TTL is deliberately huge: the
        // pre-2.3.0 bug applied this local default to every remote
        // invalidation, regardless of the token's real remaining life.
        final ValkeyRevocationBlocklist nodeB = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(bus), new InMemoryRevocationStore(), 999
        );
        nodeA.revokeJti("short-lived", 1);
        MatcherAssert.assertThat(
            "Peer must observe the revocation immediately (synchronous "
                + "in-process broadcast)",
            nodeB.isRevokedJti("short-lived"),
            Matchers.is(true)
        );
        // Past the real 1-second TTL: with the fix (real TTL embedded in
        // the pub/sub payload) node B must have expired the entry. Under
        // the pre-2.3.0 bug it would still report revoked here (999s
        // default applied instead).
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .until(() -> !nodeB.isRevokedJti("short-lived"));
    }

    @Test
    @Timeout(10)
    void revokeUserPropagatesWithItsOwnRealTtlToo() {
        final InMemoryCacheBroadcast.Bus bus = new InMemoryCacheBroadcast.Bus();
        final ValkeyRevocationBlocklist nodeA = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(bus), new InMemoryRevocationStore(), 100
        );
        final ValkeyRevocationBlocklist nodeB = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(bus), new InMemoryRevocationStore(), 999
        );
        nodeA.revokeUser("short-lived-user", 1);
        MatcherAssert.assertThat(
            nodeB.isRevokedUser("short-lived-user"), Matchers.is(true)
        );
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
            .until(() -> !nodeB.isRevokedUser("short-lived-user"));
    }

    @Test
    @Timeout(10)
    void revokeIsDbDurableBeforeItIsBroadcast() {
        final InMemoryRevocationStore store = new InMemoryRevocationStore();
        final ValkeyRevocationBlocklist node = new ValkeyRevocationBlocklist(
            new InMemoryCacheBroadcast(new InMemoryCacheBroadcast.Bus()), store, 3600
        );
        node.revokeJti("durable", 3600);
        MatcherAssert.assertThat(
            "revokeJti must write the DB row — the durable, authoritative "
                + "record a Valkey outage can never lose",
            store.insertCalls(),
            Matchers.is(1)
        );
    }
}
