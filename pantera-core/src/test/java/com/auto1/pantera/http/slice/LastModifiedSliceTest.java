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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Test for {@link LastModifiedSlice}.
 *
 * @since 2.2.12
 */
final class LastModifiedSliceTest {

    /**
     * Time the stored artifact was last written.
     */
    private static final Instant STORED = Instant.parse("2018-06-17T18:35:49Z");

    /**
     * {@link #STORED} as an HTTP date.
     */
    private static final String STORED_DATE = "Sun, 17 Jun 2018 18:35:49 GMT";

    /**
     * Time of the request.
     */
    private static final Instant NOW = Instant.parse("2026-11-03T09:05:07Z");

    /**
     * {@link #NOW} as an HTTP date.
     */
    private static final String NOW_DATE = "Tue, 03 Nov 2026 09:05:07 GMT";

    /**
     * Stored artifact.
     */
    private static final String PATH = "/org/example/tool/1.0/tool-1.0-bin.zip";

    /**
     * Artifact bytes.
     */
    private static final byte[] DATA = "archive".getBytes(StandardCharsets.UTF_8);

    /**
     * How long the slice under test waits for a storage time: long enough
     * that no test but the one about a silent storage ever reaches it.
     */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * Request path to storage key: the path itself.
     */
    private static final Function<String, Optional<Key>> KEYS =
        path -> Optional.of(new KeyFromPath(path));

    /**
     * Storage holding {@link #PATH}, last written at {@link #STORED}.
     */
    private Storage storage;

    @BeforeEach
    void init(@TempDir final Path dir) throws Exception {
        this.storage = new FileStorage(dir);
        this.storage.save(
            new KeyFromPath(LastModifiedSliceTest.PATH),
            new Content.From(LastModifiedSliceTest.DATA)
        ).get(30, TimeUnit.SECONDS);
        Files.setLastModifiedTime(
            dir.resolve(LastModifiedSliceTest.PATH.substring(1)),
            FileTime.from(LastModifiedSliceTest.STORED)
        );
    }

    @ParameterizedTest
    @EnumSource(value = RqMethod.class, names = {"GET", "HEAD"})
    void datesStoredArtifactWithItsStorageTime(final RqMethod method) throws Exception {
        MatcherAssert.assertThat(
            this.dated(LastModifiedSliceTest.plain()).response(
                new RequestLine(method, LastModifiedSliceTest.PATH),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.STORED_DATE))
        );
    }

    @Test
    void datesPartialContent() throws Exception {
        MatcherAssert.assertThat(
            this.dated(
                new SliceSimple(
                    ResponseBuilder.from(RsStatus.PARTIAL_CONTENT).body(new byte[]{1}).build()
                )
            ).response(
                new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.STORED_DATE))
        );
    }

    @Test
    void datesUnstoredPathWithRequestTime() throws Exception {
        // A proxy answering a cache miss streams the artifact while it is
        // being stored: the copy is as of now.
        MatcherAssert.assertThat(
            this.dated(LastModifiedSliceTest.plain()).response(
                new RequestLine(RqMethod.GET, "/org/example/tool/2.0/tool-2.0-bin.zip"),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
    }

    @Test
    void neverDatesInTheFuture() throws Exception {
        // A storage time ahead of the server clock (clock skew between the
        // storage and this node) is capped: Last-Modified may not be later
        // than the time the response is produced.
        final Instant earlier = Instant.parse("2010-01-01T00:00:00Z");
        MatcherAssert.assertThat(
            new LastModifiedSlice(
                LastModifiedSliceTest.plain(), Optional.of(this.storage),
                LastModifiedSliceTest.KEYS, Clock.fixed(earlier, ZoneOffset.UTC),
                LastModifiedSliceTest.PATIENCE
            ).response(
                new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).headers().values("Last-Modified"),
            new IsEqual<>(List.of("Fri, 01 Jan 2010 00:00:00 GMT"))
        );
    }

    @Test
    void keepsDateSetByOriginWithoutAskingStorage() throws Exception {
        final String upstream = "Wed, 10 Apr 2024 14:25:45 GMT";
        final CountingStorage counting = new CountingStorage(this.storage);
        final Response response = new LastModifiedSlice(
            new SliceSimple(
                ResponseBuilder.ok().header("last-modified", upstream)
                    .body(LastModifiedSliceTest.DATA).build()
            ),
            Optional.of(counting), LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the value the adapter set is the only one",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(upstream))
        );
        MatcherAssert.assertThat(
            "storage is not asked when the adapter already dated the response",
            counting.lookups(), new IsEqual<>(0)
        );
    }

    @Test
    void keepsStatusHeadersAndBody() throws Exception {
        final Response response = this.dated(
            // A HEAD answer: Content-Length of the artifact, empty body.
            new SliceSimple(
                new Response(
                    RsStatus.OK,
                    Headers.from("Content-Length", "9001022").add("Content-Type", "application/zip"),
                    new Content.From(LastModifiedSliceTest.DATA)
                )
            )
        ).response(
            new RequestLine(RqMethod.HEAD, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "status is unchanged", response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Content-Length is the one the origin announced",
            response.headers().values("Content-Length"), new IsEqual<>(List.of("9001022"))
        );
        MatcherAssert.assertThat(
            "Content-Type is unchanged",
            response.headers().values("Content-Type"), new IsEqual<>(List.of("application/zip"))
        );
        MatcherAssert.assertThat(
            "the body is the origin's body",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(LastModifiedSliceTest.DATA)
        );
    }

    @ParameterizedTest
    @EnumSource(value = RqMethod.class, names = {"PUT", "POST", "DELETE"})
    void leavesWritesAlone(final RqMethod method) throws Exception {
        final CountingStorage counting = new CountingStorage(this.storage);
        final Response response = new LastModifiedSlice(
            LastModifiedSliceTest.plain(), Optional.of(counting),
            LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(method, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a write is not dated",
            response.headers().values("Last-Modified"), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "a write does not cost a storage lookup",
            counting.lookups(), new IsEqual<>(0)
        );
    }

    @ParameterizedTest
    @EnumSource(
        value = RsStatus.class,
        names = {"NOT_MODIFIED", "NOT_FOUND", "FORBIDDEN", "BAD_GATEWAY", "SERVICE_UNAVAILABLE"}
    )
    void leavesOtherStatusesAlone(final RsStatus status) throws Exception {
        final CountingStorage counting = new CountingStorage(this.storage);
        final Response response = new LastModifiedSlice(
            new SliceSimple(ResponseBuilder.from(status).build()), Optional.of(counting),
            LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "only a served representation is dated",
            response.headers().values("Last-Modified"), new IsEqual<>(List.of())
        );
        MatcherAssert.assertThat(
            "status is relayed", response.status(), new IsEqual<>(status)
        );
        MatcherAssert.assertThat(
            "no storage lookup for a response that is not dated",
            counting.lookups(), new IsEqual<>(0)
        );
    }

    @Test
    void servesWithRequestTimeWhenStorageFails() throws Exception {
        final Storage broken = new Storage.Wrap(this.storage) {
            @Override
            public CompletableFuture<? extends Meta> metadata(final Key key) {
                return CompletableFuture.failedFuture(new IOException("storage is down"));
            }
        };
        final Response response = new LastModifiedSlice(
            LastModifiedSliceTest.plain(), Optional.of(broken),
            LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a failed metadata read never fails the download",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the response is still dated",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
        MatcherAssert.assertThat(
            "the body is intact",
            response.body().asBytesFuture().get(30, TimeUnit.SECONDS),
            new IsEqual<>(LastModifiedSliceTest.DATA)
        );
    }

    @Test
    void servesWithRequestTimeWhenStorageThrows() throws Exception {
        // A storage that throws instead of returning a failed future.
        final Storage broken = new Storage.Wrap(this.storage) {
            @Override
            public CompletableFuture<? extends Meta> metadata(final Key key) {
                throw new IllegalStateException("invalid key");
            }
        };
        final Response response = new LastModifiedSlice(
            LastModifiedSliceTest.plain(), Optional.of(broken),
            LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the download is served",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the response is dated with the request time",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
    }

    @Test
    void servesWithRequestTimeWhenKeyResolutionThrows() throws Exception {
        final Response response = new LastModifiedSlice(
            LastModifiedSliceTest.plain(), Optional.of(this.storage),
            path -> {
                throw new IllegalArgumentException("unparseable path");
            },
            LastModifiedSliceTest.clock(), LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the download is served",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the response is dated with the request time",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
    }

    @Test
    // The bound only turns a response held by the storage into a failure.
    @Timeout(30)
    void doesNotWaitForStorageThatDoesNotAnswer() throws Exception {
        final Storage silent = new Storage.Wrap(this.storage) {
            @Override
            public CompletableFuture<? extends Meta> metadata(final Key key) {
                return new CompletableFuture<>();
            }
        };
        final Response response = new LastModifiedSlice(
            LastModifiedSliceTest.plain(), Optional.of(silent),
            LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            Duration.ofMillis(20)
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get();
        MatcherAssert.assertThat(
            "the download is served",
            response.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the response is dated with the request time",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
    }

    @Test
    void datesWithRequestTimeWithoutStorage() throws Exception {
        // A group repository has no storage of its own.
        MatcherAssert.assertThat(
            new LastModifiedSlice(
                LastModifiedSliceTest.plain(), Optional.empty(),
                LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
            ).response(
                new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
                Headers.EMPTY, Content.EMPTY
            ).get(30, TimeUnit.SECONDS).headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
    }

    @Test
    void datesWithRequestTimeWhenPathHasNoStorageKey() throws Exception {
        final CountingStorage counting = new CountingStorage(this.storage);
        final Response response = new LastModifiedSlice(
            LastModifiedSliceTest.plain(), Optional.of(counting),
            path -> Optional.empty(), LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        ).response(
            new RequestLine(RqMethod.GET, LastModifiedSliceTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the response is dated with the request time",
            response.headers().values("Last-Modified"),
            new IsEqual<>(List.of(LastModifiedSliceTest.NOW_DATE))
        );
        MatcherAssert.assertThat(
            "no storage lookup without a key",
            counting.lookups(), new IsEqual<>(0)
        );
    }

    /**
     * The slice under test over the storage of this test.
     * @param origin Origin slice
     * @return Dated slice
     */
    private LastModifiedSlice dated(final Slice origin) {
        return new LastModifiedSlice(
            origin, Optional.of(this.storage),
            LastModifiedSliceTest.KEYS, LastModifiedSliceTest.clock(),
            LastModifiedSliceTest.PATIENCE
        );
    }

    /**
     * Origin that answers 200 with the artifact and no Last-Modified.
     * @return Origin slice
     */
    private static Slice plain() {
        return new SliceSimple(ResponseBuilder.ok().body(LastModifiedSliceTest.DATA).build());
    }

    private static Clock clock() {
        return Clock.fixed(LastModifiedSliceTest.NOW, ZoneOffset.UTC);
    }

    /**
     * Storage counting metadata and existence lookups.
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
