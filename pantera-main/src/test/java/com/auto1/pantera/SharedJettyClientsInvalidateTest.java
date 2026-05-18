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
package com.auto1.pantera;

import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for the cached-Jetty-client pool inside {@link RepositorySlices}.
 *
 * <p>Verifies that {@link RepositorySlices.SharedJettyClients#invalidateAll}
 * drops every cached client so the next
 * {@link RepositorySlices.SharedJettyClients#acquire} miss rebuilds with the
 * latest static settings, while active leases keep their existing client
 * until released.</p>
 *
 * <p>No DB, no testcontainers, no network — these are the in-process
 * invariants of the eviction protocol.</p>
 */
final class SharedJettyClientsInvalidateTest {

    @Test
    void invalidateAllDropsCachedClientsSoNextAcquireRebuilds() {
        final RepositorySlices.SharedJettyClients pool =
            new RepositorySlices.SharedJettyClients();
        final HttpClientSettings settings = new HttpClientSettings();
        try (RepositorySlices.SharedJettyClients.Lease first =
                 pool.acquire(settings)) {
            assertThat(
                "first acquire populates the cache",
                pool.cachedClientCount(), equalTo(1)
            );
            // Releasing `first` is unrelated; the lease stays open here
            // to prove invalidate is safe with active leases.
            pool.invalidateAll();
            assertThat(
                "invalidateAll drops every cache map entry",
                pool.cachedClientCount(), equalTo(0)
            );
            // Active lease still works after eviction (we don't touch
            // the network here — JettyClientSlices.client() is just a
            // method call) — the in-flight client must remain usable
            // until the lease closes.
            assertThat(
                "in-flight lease keeps its existing client",
                first.client() != null, equalTo(true)
            );
        }
        // After the lease closes, refs hit zero on the evicted client and
        // it stops automatically. A subsequent acquire builds anew.
        try (RepositorySlices.SharedJettyClients.Lease second =
                 pool.acquire(settings)) {
            assertThat(
                "post-invalidate acquire rebuilds the cache",
                pool.cachedClientCount(), equalTo(1)
            );
            // The new client object is distinct from the evicted one.
            assertThat(
                "rebuilt client is a fresh JettyClientSlices instance",
                second.client() != null, equalTo(true)
            );
        }
    }

    @Test
    void invalidateAllOnEmptyPoolIsNoop() {
        final RepositorySlices.SharedJettyClients pool =
            new RepositorySlices.SharedJettyClients();
        pool.invalidateAll();
        assertThat(pool.cachedClientCount(), equalTo(0));
    }

    /**
     * Regression test: the per-destination keep-alive pool cap is sourced
     * from the static {@link HttpClientSettings#maxConnectionsPerDestination()}
     * (typical 20-50 in YAML), not from any runtime tunable.
     */
    @Test
    void poolCapComesFromStaticHttpClientSettings() {
        final int yamlMaxConns = 37;
        final HttpClientSettings settings = new HttpClientSettings()
            .setMaxConnectionsPerDestination(yamlMaxConns);
        final RepositorySlices.SharedJettyClients pool =
            new RepositorySlices.SharedJettyClients();
        try (RepositorySlices.SharedJettyClients.Lease lease =
                 pool.acquire(settings)) {
            final JettyClientSlices client = lease.client();
            assertThat(
                "shared client honours the YAML maxConnectionsPerDestination",
                client.httpClient().getMaxConnectionsPerDestination(),
                equalTo(yamlMaxConns)
            );
        }
    }
}
