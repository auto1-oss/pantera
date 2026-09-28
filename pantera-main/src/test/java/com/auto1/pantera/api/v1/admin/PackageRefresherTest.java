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
package com.auto1.pantera.api.v1.admin;

import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metadata.ProxyMetadataRevalidators;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * {@link PackageRefresher}: raw metadata revalidated first, then envelopes
 * and negative-cache entries dropped, with the package inspected before
 * and after.
 *
 * @since 2.2.9
 */
final class PackageRefresherTest {

    @Test
    void refreshesEveryLayerInOrderAndReportsBeforeAndAfter() throws Exception {
        final String proxy = "npm_proxy_" + UUID.randomUUID();
        final FakeTopology topo = new FakeTopology()
            .proxy(proxy, "npm-proxy")
            .group("grp_" + proxy, "npm-group", proxy);
        final FakeFetch fetch = new FakeFetch()
            .answer(proxy, "/openai", 200, "{\"versions\":{\"1.0.0\":{}}}");
        final List<String> order = new CopyOnWriteArrayList<>();
        ProxyMetadataRevalidators.instance().register(proxy, pkg -> {
            order.add("revalidate:" + pkg);
            return CompletableFuture.completedFuture("revalidated");
        });
        final FilteredMetadataCache envelopes = new FilteredMetadataCache(
            100, Duration.ofMinutes(5), Duration.ofMinutes(5), null
        );
        envelopes.getEntry(
            "npm-proxy", proxy, "full", "openai",
            () -> CompletableFuture.completedFuture(FilteredMetadataCache.CacheEntry
                .noBlockedVersions("{}".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)))
        ).get(5, TimeUnit.SECONDS);
        final NegativeCache negative = new NegativeCache(new NegativeCacheConfig());
        negative.cacheNotFound(new NegativeCacheKey(proxy, "npm-proxy", "openai", "2.0.0"));
        final PackageInspector inspector = new PackageInspector(
            new AdminDiagnostics(topo, fetch, BreakerProbe.NONE, "n"),
            negative, () -> Optional.of(envelopes), CooldownLookup.NONE
        );
        try {
            final JsonObject res = new PackageRefresher(
                inspector, negative, () -> Optional.of(envelopes),
                ProxyMetadataRevalidators.instance()
            ).refresh("npm", "openai", null, null).get(10, TimeUnit.SECONDS);
            MatcherAssert.assertThat(
                "the proxy's raw metadata was revalidated with the typed name",
                order, new IsEqual<>(List.of("revalidate:openai"))
            );
            MatcherAssert.assertThat(
                "the envelope was dropped",
                res.getJsonObject("cleared").getJsonObject("envelopes").getInteger("l1"),
                new IsEqual<>(1)
            );
            MatcherAssert.assertThat(
                "the cached 404 was dropped",
                res.getJsonObject("cleared").getJsonObject("negativeCache").getInteger("l1"),
                new IsEqual<>(1)
            );
            MatcherAssert.assertThat(
                "before shows the envelope, after does not",
                List.of(
                    PackageRefresherTest.envelopeInL1(res.getJsonObject("before"), proxy),
                    PackageRefresherTest.envelopeInL1(res.getJsonObject("after"), proxy)
                ),
                new IsEqual<>(List.of(true, false))
            );
            MatcherAssert.assertThat(
                "the group is not revalidated itself (only proxies are)",
                res.getJsonArray("revalidated").size(), new IsEqual<>(1)
            );
        } finally {
            ProxyMetadataRevalidators.instance().remove(proxy);
        }
    }

    /**
     * Envelope L1 presence of a repository in an inspection.
     *
     * @param doc Inspection
     * @param repo Repository
     * @return Presence
     */
    private static boolean envelopeInL1(final JsonObject doc, final String repo) {
        for (int idx = 0; idx < doc.getJsonArray("repos").size(); idx = idx + 1) {
            final JsonObject item = doc.getJsonArray("repos").getJsonObject(idx);
            if (repo.equals(item.getString("name"))) {
                return item.getJsonObject("envelope").getJsonObject("l1").getBoolean("present");
            }
        }
        throw new AssertionError("no repo " + repo);
    }
}
