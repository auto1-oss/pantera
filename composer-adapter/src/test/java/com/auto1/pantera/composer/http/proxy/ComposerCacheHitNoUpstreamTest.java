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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.composer.cooldown.ComposerCooldownResponseFactory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Track 5 Phase 1A acceptance test for the composer adapter: a cache hit
 * for either metadata JSON or a primary dist archive MUST NOT touch the
 * upstream client OR the cooldown inspector. Pre-Track-5 the metadata
 * cache-hit branch called {@code evaluateMetadataCooldown}, which went
 * through {@code RegistryBackedInspector} → {@code PackagistSource} on
 * L1+L2 miss — same shape as the maven {@code MavenHeadSource} flaw.
 *
 * @since 2.2.0
 */
final class ComposerCacheHitNoUpstreamTest {

    @BeforeEach
    void setUp() {
        CooldownResponseRegistry.instance().register(
            "php", new ComposerCooldownResponseFactory()
        );
    }

    @Test
    @DisplayName("cached metadata JSON is served pure-local — no upstream + no inspector calls")
    void metadataCacheHitIsLocal() {
        final String pkg = "vendor/package";
        final byte[] cachedJson =
            ("{\"packages\":{\"" + pkg + "\":{\"1.0\":{\"version\":\"1.0\","
                + "\"time\":\"2024-01-01T00:00:00+00:00\"}}}}").getBytes(StandardCharsets.UTF_8);
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From(pkg), new Content.From(cachedJson)).join();

        final AtomicInteger upstreamCalls = new AtomicInteger();
        final AtomicInteger inspectorCalls = new AtomicInteger();
        final AtomicInteger cooldownEvaluations = new AtomicInteger();

        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return CompletableFuture.failedFuture(
                new AssertionError("upstream must not be called on cache hit")
            );
        };
        final CooldownService cooldown = new CooldownService() {
            @Override
            public CompletableFuture<CooldownResult> evaluate(
                final CooldownRequest req, final CooldownInspector inspector
            ) {
                cooldownEvaluations.incrementAndGet();
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            @Override
            public CompletableFuture<Void> unblock(
                final String rt, final String rn, final String art,
                final String ver, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<Void> unblockAll(
                final String rt, final String rn, final String actor
            ) {
                return CompletableFuture.completedFuture(null);
            }
            @Override
            public CompletableFuture<List<com.auto1.pantera.cooldown.api.CooldownBlock>>
                activeBlocks(final String rt, final String rn) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CooldownInspector inspector = new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                inspectorCalls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                inspectorCalls.incrementAndGet();
                return CompletableFuture.completedFuture(List.of());
            }
        };
        final CachedProxySlice slice = new CachedProxySlice(
            upstream,
            new AstoRepository(storage),
            new FromStorageCache(storage),
            Optional.empty(),
            "composer_proxy",
            "php",
            cooldown,
            inspector,
            "http://localhost:8080",
            "https://packagist.org"
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/p2/" + pkg + ".json"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "200 OK from cache hit",
            response.status(),
            new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "upstream MUST NOT be called on cache hit",
            upstreamCalls.get(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "inspector MUST NOT be called on cache hit (no PackagistSource fallback)",
            inspectorCalls.get(),
            new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "cooldown evaluator MUST NOT run on cache hit (Track 5 Phase 1A)",
            cooldownEvaluations.get(),
            new IsEqual<>(0)
        );
    }
}
