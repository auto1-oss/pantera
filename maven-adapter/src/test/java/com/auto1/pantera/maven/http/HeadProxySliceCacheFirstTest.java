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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import com.auto1.pantera.http.cache.CachedArtifactMetadataStore;
import java.util.List;
import java.util.Map;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Track 5 Phase 2B acceptance test for the Maven {@code HeadProxySlice}:
 * on a cached artifact, the HEAD response must come from local storage
 * metadata without ever touching upstream. Cache miss still proxies.
 *
 * @since 2.2.0
 */
final class HeadProxySliceCacheFirstTest {

    @Test
    @DisplayName("HEAD on a cached jar returns 200 + Content-Length without touching upstream")
    void headHitServesLocalMetadata() {
        final String path = "/com/example/lib/1.0/lib-1.0.jar";
        final byte[] bytes = "cached-jar-bytes".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path.substring(1)), new Content.From(bytes)).join();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return CompletableFuture.failedFuture(
                new AssertionError("upstream must not be hit on cache HEAD")
            );
        };
        final HeadProxySlice slice = new HeadProxySlice(upstream, Optional.of(storage));
        final Response resp = slice.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "200 OK from local storage", resp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "zero upstream calls",
            upstreamCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    @DisplayName("HEAD on a cached file relays the Last-Modified its GET relays")
    void headHitRelaysRecordedLastModified() {
        // The standard cache flow records the upstream response headers next
        // to the file and a GET of the cached file relays them; HEAD must
        // announce the same date.
        final String path = "/org/example/dist/1.0/dist-1.0-bin.tar.gz";
        final String recorded = "Sun, 17 Jun 2018 18:35:49 GMT";
        final byte[] bytes = "cached-archive".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From(path.substring(1));
        storage.save(key, new Content.From(bytes)).join();
        new CachedArtifactMetadataStore(storage).save(
            key,
            Headers.from("Last-Modified", recorded),
            new CachedArtifactMetadataStore.ComputedDigests(bytes.length, Map.of())
        ).join();
        final Response resp = new HeadProxySlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new AssertionError("upstream must not be hit on cache HEAD")
            ),
            Optional.of(storage)
        ).response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "200 OK from local storage", resp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the recorded upstream date is relayed",
            resp.headers().values("Last-Modified"), new IsEqual<>(List.of(recorded))
        );
        MatcherAssert.assertThat(
            "Content-Length is the cached file's size",
            resp.headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(bytes.length)))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // A primary artifact.
        "/org/apache/maven/apache-maven/3.5.4/apache-maven-3.5.4-bin.zip",
        // A file of the standard cache flow whose upstream sent no date.
        "/org/example/dist/1.0/dist-1.0-bin.tar.gz"
    })
    @DisplayName("HEAD on a cached file without a recorded date reports its storage time")
    void headHitReportsStorageTime(final String path, @TempDir final Path dir)
        throws Exception {
        final Key key = new Key.From(path.substring(1));
        final List<String> looked = new CopyOnWriteArrayList<>();
        final Storage storage = new Storage.Wrap(new FileStorage(dir)) {
            @Override
            public CompletableFuture<Boolean> exists(final Key target) {
                looked.add(target.string());
                return super.exists(target);
            }
        };
        storage.save(key, new Content.From("cached".getBytes(StandardCharsets.UTF_8)))
            .get(30, TimeUnit.SECONDS);
        Files.setLastModifiedTime(
            dir.resolve(key.string()), FileTime.from(Instant.parse("2018-06-17T18:35:49Z"))
        );
        final Response resp = new HeadProxySlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new AssertionError("upstream must not be hit on cache HEAD")
            ),
            Optional.of(storage)
        ).response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the storage time is reported",
            resp.headers().values("Last-Modified"),
            new IsEqual<>(List.of("Sun, 17 Jun 2018 18:35:49 GMT"))
        );
        MatcherAssert.assertThat(
            "a primary artifact has no recorded headers to look for",
            looked.contains(key.string() + ".pantera-meta.json"),
            new IsEqual<>(!path.endsWith(".zip"))
        );
    }

    @Test
    @DisplayName("HEAD on a missing artifact falls through to upstream HEAD")
    void headMissDelegatesToUpstream() {
        final InMemoryStorage storage = new InMemoryStorage();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return ResponseBuilder.ok().completedFuture();
        };
        final HeadProxySlice slice = new HeadProxySlice(upstream, Optional.of(storage));
        final Response resp = slice.response(
            new RequestLine(RqMethod.HEAD, "/missing/lib/1.0/lib-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "upstream called exactly once",
            upstreamCalls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "200 OK forwarded from upstream",
            resp.status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    @DisplayName("HEAD with no storage falls back to pre-Track-5 pass-through")
    void headWithoutStoragePassesThrough() {
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return ResponseBuilder.ok().completedFuture();
        };
        final HeadProxySlice slice = new HeadProxySlice(upstream);
        slice.response(
            new RequestLine(RqMethod.HEAD, "/anything"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "no-storage variant is pure pass-through",
            upstreamCalls.get(), new IsEqual<>(1)
        );
    }
}
