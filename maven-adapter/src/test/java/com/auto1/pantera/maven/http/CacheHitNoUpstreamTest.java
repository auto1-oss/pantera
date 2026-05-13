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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Track 5 Phase 1A acceptance test: on a cache hit for a Maven primary
 * artifact, the proxy slice MUST NOT touch the upstream client OR call the
 * cooldown inspector. Pre-Track-5 every primary request ran
 * {@code evaluateCooldownOrProceed} BEFORE the storage.exists check; the
 * inspector then fell through to {@code MavenHeadSource} on L1+L2 miss,
 * generating a HEAD to Maven Central per cached request. Under
 * Cloudflare-backed rate limiting that path was the dominant source of
 * 429 throttling.
 *
 * @since 2.2.0
 */
final class CacheHitNoUpstreamTest {

    @BeforeEach
    void setUp() {
        CooldownResponseRegistry.instance().register(
            "maven-proxy", new MavenCooldownResponseFactory()
        );
    }

    @Test
    @DisplayName("cache hit on .jar serves local bytes — zero upstream, zero inspector calls")
    void cacheHitJarIsPureLocal() {
        final String path = "/com/example/lib/1.0/lib-1.0.jar";
        final byte[] cachedBytes = "cached".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path.substring(1)), new Content.From(cachedBytes)).join();

        final AtomicInteger upstreamCalls = new AtomicInteger();
        final AtomicInteger inspectorCalls = new AtomicInteger();
        final Queue<ProxyArtifactEvent> events = new LinkedBlockingQueue<>();

        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                upstreamCalls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new AssertionError("Upstream must not be called on cache hit")
                );
            },
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(events),
            "maven_proxy",
            "https://repo.maven.apache.org/maven2",
            "maven-proxy",
            NoopCooldownService.INSTANCE,
            countingInspector(inspectorCalls),
            Optional.of(storage),
            ProxyCacheConfig.withCooldown(),
            null
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "200 OK from cache hit",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "upstream MUST NOT be touched on cache hit",
            upstreamCalls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "cooldown inspector MUST NOT be called on cache hit",
            inspectorCalls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "response body is the cached bytes",
            new String(response.body().asBytes(), StandardCharsets.UTF_8),
            new IsEqual<>("cached")
        );
    }

    @Test
    @DisplayName("cache hit on .pom serves local bytes — zero upstream, zero inspector calls")
    void cacheHitPomIsPureLocal() {
        final String path = "/com/example/lib/1.0/lib-1.0.pom";
        final byte[] cachedBytes = "<project/>".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path.substring(1)), new Content.From(cachedBytes)).join();

        final AtomicInteger upstreamCalls = new AtomicInteger();
        final AtomicInteger inspectorCalls = new AtomicInteger();
        final Queue<ProxyArtifactEvent> events = new LinkedBlockingQueue<>();

        final CachedProxySlice slice = new CachedProxySlice(
            (line, headers, body) -> {
                upstreamCalls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new AssertionError("Upstream must not be called on cache hit")
                );
            },
            (cacheKey, supplier, control) -> supplier.get(),
            Optional.of(events),
            "maven_proxy",
            "https://repo.maven.apache.org/maven2",
            "maven-proxy",
            NoopCooldownService.INSTANCE,
            countingInspector(inspectorCalls),
            Optional.of(storage),
            ProxyCacheConfig.withCooldown(),
            null
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();

        MatcherAssert.assertThat(
            "200 OK",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "no upstream calls",
            upstreamCalls.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "no inspector calls",
            inspectorCalls.get(), new IsEqual<>(0)
        );
    }

    private static CooldownInspector countingInspector(final AtomicInteger calls) {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
