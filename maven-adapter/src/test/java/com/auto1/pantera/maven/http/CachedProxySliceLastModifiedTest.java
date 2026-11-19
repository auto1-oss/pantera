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
import com.auto1.pantera.asto.Meta;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.text.MatchesPattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A primary artifact served by the Maven proxy carries {@code Last-Modified}:
 * the Jenkins tool installer downloads
 * {@code apache-maven-<version>-bin.zip} through a Maven proxy and refuses a
 * response without it.
 *
 * <p>The proxy dates the response itself, so the hot cache-hit path costs no
 * storage read beyond the one that tells it the artifact is cached.</p>
 *
 * @since 2.2.12
 */
final class CachedProxySliceLastModifiedTest {

    /**
     * Primary artifact.
     */
    private static final String PATH =
        "/org/apache/maven/apache-maven/3.5.4/apache-maven-3.5.4-bin.zip";

    /**
     * Artifact bytes.
     */
    private static final byte[] DATA = "zip bytes".getBytes(StandardCharsets.UTF_8);

    /**
     * Fixed-length HTTP date.
     */
    private static final String HTTP_DATE =
        "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";

    @Test
    void cacheHitIsDatedWithStorageTimeAtNoExtraStorageRead(@TempDir final Path dir)
        throws Exception {
        final Key key = new Key.From(CachedProxySliceLastModifiedTest.PATH.substring(1));
        final CountingStorage storage = new CountingStorage(new FileStorage(dir));
        storage.save(key, new Content.From(CachedProxySliceLastModifiedTest.DATA))
            .get(30, TimeUnit.SECONDS);
        Files.setLastModifiedTime(
            dir.resolve(key.string()), FileTime.from(Instant.parse("2018-06-17T18:35:49Z"))
        );
        final Response response = CachedProxySliceLastModifiedTest.slice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new AssertionError("upstream must not be asked on a cache hit")
            ),
            storage
        ).response(
            new RequestLine(RqMethod.GET, CachedProxySliceLastModifiedTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the cached artifact is served", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "it is dated with the time it was stored",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of("Sun, 17 Jun 2018 18:35:49 GMT"))
        );
        MatcherAssert.assertThat(
            "the body is the cached artifact",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceLastModifiedTest.DATA)
        );
        MatcherAssert.assertThat(
            "presence and date come from one storage lookup",
            storage.lookups(), new IsEqual<>(1)
        );
    }

    @Test
    void cacheMissIsDated(@TempDir final Path dir) throws Exception {
        final String sha1 = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-1").digest(CachedProxySliceLastModifiedTest.DATA)
        );
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            line.uri().getPath().endsWith(".sha1")
                ? ResponseBuilder.ok().body(sha1.getBytes(StandardCharsets.UTF_8)).build()
                : ResponseBuilder.ok().body(CachedProxySliceLastModifiedTest.DATA).build()
        );
        final Storage storage = new FileStorage(dir);
        final Response response = CachedProxySliceLastModifiedTest.slice(
            upstream, storage
        ).response(
            new RequestLine(RqMethod.GET, CachedProxySliceLastModifiedTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the artifact is served from upstream", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "it carries exactly one Last-Modified",
            response.headers().values("Last-Modified").size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the date is a fixed-length HTTP date",
            response.headers().values("Last-Modified").get(0),
            new MatchesPattern(
                java.util.regex.Pattern.compile(CachedProxySliceLastModifiedTest.HTTP_DATE)
            )
        );
        MatcherAssert.assertThat(
            "the body is the upstream artifact",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceLastModifiedTest.DATA)
        );
        // The cache commit runs after the body was streamed: wait for it so
        // the temp directory is not removed under a write in progress.
        final Key key = new Key.From(CachedProxySliceLastModifiedTest.PATH.substring(1));
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!storage.exists(key).get(30, TimeUnit.SECONDS) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        MatcherAssert.assertThat(
            "the artifact is cached",
            storage.exists(key).get(30, TimeUnit.SECONDS), new IsEqual<>(true)
        );
    }

    private static CachedProxySlice slice(final Slice upstream, final Storage storage) {
        return new CachedProxySlice(
            upstream,
            new FromStorageCache(storage),
            Optional.empty(), "maven_proxy", "https://repo1.maven.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE,
            new CooldownInspector() {
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
            },
            Optional.of(storage)
        );
    }

    /**
     * Storage counting existence and metadata lookups.
     */
    private static final class CountingStorage extends Storage.Wrap {

        /**
         * Lookups made.
         */
        private final AtomicInteger count;

        CountingStorage(final Storage origin) {
            super(origin);
            this.count = new AtomicInteger();
        }

        @Override
        public CompletableFuture<Boolean> exists(final Key key) {
            this.count.incrementAndGet();
            return super.exists(key);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            this.count.incrementAndGet();
            return super.metadata(key);
        }

        int lookups() {
            return this.count.get();
        }
    }
}
