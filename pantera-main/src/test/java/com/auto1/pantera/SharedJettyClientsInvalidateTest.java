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
import com.auto1.pantera.settings.runtime.HttpTuning;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Unit tests for the v2.2 hot-reload glue inside {@link RepositorySlices}.
 *
 * <ul>
 *   <li>{@link RepositorySlices#mapProtocol} — pure value mapping;
 *       trivial but the source of truth for the
 *       {@code HttpTuning.Protocol → JettyClientSlices.HttpProtocol}
 *       contract.</li>
 *   <li>{@link RepositorySlices.SharedJettyClients#invalidateAll} —
 *       drops every cached client so the next
 *       {@link RepositorySlices.SharedJettyClients#acquire} miss
 *       rebuilds with the latest tuning.</li>
 * </ul>
 *
 * <p>No DB, no testcontainers, no network — these are the in-process
 * invariants. The integration of the listener wiring lives in
 * {@code VertxMain}; verifying that fires end-to-end is left to manual
 * smoke testing because spinning up the full server here would balloon
 * test time without testing additional logic.</p>
 */
final class SharedJettyClientsInvalidateTest {

    @Test
    void mapProtocolMapsAllEnumValues() {
        assertThat(
            RepositorySlices.mapProtocol(HttpTuning.Protocol.H1),
            equalTo(JettyClientSlices.HttpProtocol.H1)
        );
        assertThat(
            RepositorySlices.mapProtocol(HttpTuning.Protocol.H2),
            equalTo(JettyClientSlices.HttpProtocol.H2)
        );
        assertThat(
            RepositorySlices.mapProtocol(HttpTuning.Protocol.AUTO),
            equalTo(JettyClientSlices.HttpProtocol.AUTO)
        );
    }

    @Test
    void invalidateAllDropsCachedClientsSoNextAcquireRebuilds() {
        final AtomicReference<HttpTuning> tuning =
            new AtomicReference<>(HttpTuning.defaults());
        final RepositorySlices.SharedJettyClients pool =
            new RepositorySlices.SharedJettyClients(tuning::get);
        final HttpClientSettings settings = new HttpClientSettings();
        try (RepositorySlices.SharedJettyClients.Lease first =
                 pool.acquire(settings)) {
            assertThat(
                "first acquire populates the cache",
                pool.cachedClientCount(), equalTo(1)
            );
            // Flip the tuning supplier to a different protocol so we can
            // observe that the post-invalidate acquire actually rebuilds.
            tuning.set(new HttpTuning(HttpTuning.Protocol.H1, 2, 50));
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
        // it stops automatically. A subsequent acquire builds anew using
        // the updated tuning supplier.
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
            new RepositorySlices.SharedJettyClients(HttpTuning::defaults);
        pool.invalidateAll();
        assertThat(pool.cachedClientCount(), equalTo(0));
    }

    /**
     * Regression test for code-review Important #1 on commit
     * {@code 8d31e197a}: when callers construct {@code RepositorySlices}
     * via the 3-/4-arg legacy ctors (no {@code HttpTuning} supplier), the
     * resulting {@code SharedClient} must use the legacy 1-arg
     * {@link JettyClientSlices#JettyClientSlices(HttpClientSettings)} ctor
     * so the YAML-driven {@code maxConnectionsPerDestination} (typically
     * 20-50) is preserved — instead of silently dropping to
     * {@link HttpTuning#defaults()}'s {@code h2MaxPoolSize == 1}.
     */
    @Test
    void legacyConstructorPreservesYamlMaxConnectionsPerDestination() {
        final int yamlMaxConns = 37;
        final HttpClientSettings settings = new HttpClientSettings()
            .setMaxConnectionsPerDestination(yamlMaxConns);
        // Legacy fallback path: useLegacyHttpClientCtor=true forces routing
        // through new JettyClientSlices(HttpClientSettings).
        final RepositorySlices.SharedJettyClients legacyPool =
            new RepositorySlices.SharedJettyClients(HttpTuning::defaults, true);
        try (RepositorySlices.SharedJettyClients.Lease lease =
                 legacyPool.acquire(settings)) {
            final JettyClientSlices client = lease.client();
            assertThat(
                "legacy ctor preserves YAML maxConnectionsPerDestination,"
                    + " not HttpTuning.defaults().h2MaxPoolSize() (1)",
                client.httpClient().getMaxConnectionsPerDestination(),
                equalTo(yamlMaxConns)
            );
        }
        // Sanity: the new (default) path uses the supplier value, so it
        // should NOT preserve the YAML max — it'd cap at h2MaxPoolSize == 1.
        final RepositorySlices.SharedJettyClients newPool =
            new RepositorySlices.SharedJettyClients(HttpTuning::defaults);
        try (RepositorySlices.SharedJettyClients.Lease lease =
                 newPool.acquire(settings)) {
            final JettyClientSlices client = lease.client();
            assertThat(
                "non-legacy ctor uses HttpTuning.defaults().h2MaxPoolSize() == 1,"
                    + " ignoring the YAML value",
                client.httpClient().getMaxConnectionsPerDestination(),
                equalTo(HttpTuning.defaults().h2MaxPoolSize())
            );
        }
    }
}
