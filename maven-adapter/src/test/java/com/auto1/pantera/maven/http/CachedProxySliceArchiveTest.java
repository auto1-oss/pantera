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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Maven proxy serves the distribution archives a repository carries next to
 * its jars and poms ({@code -bin.tar.gz}, {@code .tar.bz2}, {@code .tgz},
 * native binaries). They are not one of the primary extensions, so they take
 * the standard cache flow of the proxy base; on a file storage that flow
 * answered 502 for every such file larger than a few chunks.
 *
 * @since 2.2.12
 */
final class CachedProxySliceArchiveTest {

    /**
     * Archive size: far above the size that used to fail, small enough for a
     * unit test.
     */
    private static final int SIZE = 2_000_000;

    @ParameterizedTest
    @ValueSource(strings = {
        "/org/apache/maven/apache-maven/3.5.4/apache-maven-3.5.4-bin.tar.gz",
        "/org/example/dist/1.0/dist-1.0.tar.bz2",
        "/org/example/dist/1.0/dist-1.0.tgz",
        "/org/example/native/1.0/native-1.0-linux-x86_64.so"
    })
    // The bound only turns a hung cache write into a failure.
    @Timeout(120)
    void servesAndCachesNonPrimaryArchive(final String path, @TempDir final Path dir)
        throws Exception {
        final byte[] archive = new byte[CachedProxySliceArchiveTest.SIZE];
        new Random(path.hashCode()).nextBytes(archive);
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(archive).build()
            );
        };
        final Storage storage = new FileStorage(dir);
        final CachedProxySlice slice = new CachedProxySlice(
            upstream,
            new FromStorageCache(storage),
            Optional.empty(), "maven_proxy", "https://repo1.maven.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE,
            CachedProxySliceArchiveTest.noopInspector(),
            Optional.of(storage)
        );
        final Response miss = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the archive is served on a cache miss",
            miss.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the client receives the upstream bytes",
            miss.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(archive)
        );
        MatcherAssert.assertThat(
            "the archive is cached byte for byte",
            storage.value(new Key.From(path.substring(1))).get(60, TimeUnit.SECONDS)
                .asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(archive)
        );
        final Response hit = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the archive is served on a cache hit",
            hit.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the cache hit serves the same bytes",
            hit.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(archive)
        );
        MatcherAssert.assertThat(
            "the cache hit does not contact the upstream",
            upstreamCalls.get(), new IsEqual<>(1)
        );
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
