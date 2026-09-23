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
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A metadata cache hit must be served pure-local — no upstream call.
 * Per-version cooldown evaluation is owned by
 * {@code ComposerPackageMetadataHandler} upstream of this slice, so
 * {@code CachedProxySlice} itself never invokes a cooldown service.
 *
 * @since 2.2.0
 */
final class ComposerCacheHitNoUpstreamTest {

    @Test
    @DisplayName("cached metadata JSON is served pure-local — no upstream call")
    void metadataCacheHitIsLocal(@TempDir final Path dir) {
        final String pkg = "vendor/package";
        final byte[] cachedJson =
            ("{\"packages\":{\"" + pkg + "\":{\"1.0\":{\"version\":\"1.0\","
                + "\"time\":\"2024-01-01T00:00:00+00:00\"}}}}").getBytes(StandardCharsets.UTF_8);
        // FileStorage reports updated-at, so the just-saved entry is fresh
        // and the hit is pure-local. InMemoryStorage reports no timestamp,
        // which CacheTimeControl treats as stale (background refresh).
        // Metadata is cached as <vendor>/<pkg>.json (ComposerStorageCache).
        final Key cached = new Key.From(pkg + ".json");
        final Storage storage = new FileStorage(dir);
        storage.save(cached, new Content.From(cachedJson)).join();
        MatcherAssert.assertThat(
            "precondition: the cached entry is fresh, so no background refresh is scheduled",
            new CacheTimeControl(storage).validate(cached, Remote.EMPTY)
                .toCompletableFuture().join(),
            new IsEqual<>(true)
        );

        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return CompletableFuture.failedFuture(
                new AssertionError("upstream must not be called on cache hit")
            );
        };
        final CachedProxySlice slice = new CachedProxySlice(
            upstream,
            new AstoRepository(storage),
            new FromStorageCache(storage),
            Optional.empty(),
            "composer_proxy",
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
    }
}
