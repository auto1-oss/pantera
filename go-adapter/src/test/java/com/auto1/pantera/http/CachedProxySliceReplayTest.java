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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Troubleshoot replay asks a Go proxy what a client would get. It must
 * leave the proxy as it found it: what the upstream has is answered, not
 * stored, and no event for the search index is written. A replay that
 * cached the module changed what the group index says, and with it the
 * answers of the requests that followed.
 *
 * @since 2.2.15
 */
final class CachedProxySliceReplayTest {

    /**
     * What the upstream answers with.
     */
    private static final byte[] BYTES =
        "what the upstream has".getBytes(StandardCharsets.UTF_8);

    /**
     * A module archive: the only file of a version that is announced to
     * the search index.
     */
    private static final String ZIP = "/example.com/mod/@v/v1.0.0.zip";

    @ParameterizedTest
    @ValueSource(strings = {
        CachedProxySliceReplayTest.ZIP,
        "/example.com/mod/@v/v1.0.0.mod",
        "/example.com/mod/@v/v1.0.0.info",
        "/sumdb/sum.golang.org/lookup/example.com/mod@v1.0.0",
        "/sumdb/sum.golang.org/tile/8/0/000"
    })
    @Timeout(120)
    void replayAnswersWhatTheUpstreamHasAndStoresNothing(final String path) throws Exception {
        final Upstream upstream = new Upstream(path);
        final Storage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = CachedProxySliceReplayTest.proxy(
            upstream, storage, Optional.of(storage), events
        );
        final Response replay = CachedProxySliceReplayTest.replay(slice, path);
        MatcherAssert.assertThat(
            "the replay says the file is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and hands on what the upstream sent",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceReplayTest.BYTES)
        );
        CachedProxySliceReplayTest.assertLeftAsFound(storage, events);
        CachedProxySliceReplayTest.assertClientIsStored(slice, path, storage, events);
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.asked(), new IsEqual<>(2)
        );
        final int announced = events.size();
        final Response held = CachedProxySliceReplayTest.replay(slice, path);
        MatcherAssert.assertThat(
            "a replay of a file the proxy holds is served from its cache",
            held.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceReplayTest.BYTES)
        );
        MatcherAssert.assertThat(
            "without asking the upstream", upstream.asked(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "and without an event", events.size(), new IsEqual<>(announced)
        );
    }

    @Test
    @Timeout(120)
    void replayStoresNoArchiveOfAProxyWithoutTheVerifyingWriter() throws Exception {
        final Upstream upstream = new Upstream(CachedProxySliceReplayTest.ZIP);
        final Storage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        // No storage for the writer: the archive takes the cache-load
        // path of the .mod and .info files, which queues the event itself.
        final Slice slice = CachedProxySliceReplayTest.proxy(
            upstream, storage, Optional.empty(), events
        );
        final Response replay = CachedProxySliceReplayTest.replay(
            slice, CachedProxySliceReplayTest.ZIP
        );
        MatcherAssert.assertThat(
            "the replay says the archive is there",
            replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and hands on what the upstream sent",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceReplayTest.BYTES)
        );
        CachedProxySliceReplayTest.assertLeftAsFound(storage, events);
        CachedProxySliceReplayTest.assertClientIsStored(
            slice, CachedProxySliceReplayTest.ZIP, storage, events
        );
    }

    @Test
    @Timeout(120)
    void clientWaitingBehindAReplayIsServedAndStored() throws Exception {
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final AtomicInteger asked = new AtomicInteger();
        // The first request for the archive is answered when released.
        final Slice upstream = (line, headers, body) -> {
            final CompletableFuture<Response> res;
            if (!line.uri().getPath().equals(CachedProxySliceReplayTest.ZIP)) {
                res = CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
            } else if (asked.incrementAndGet() == 1) {
                res = release.thenApply(
                    ignored -> ResponseBuilder.ok().body(CachedProxySliceReplayTest.BYTES).build()
                );
            } else {
                res = CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(CachedProxySliceReplayTest.BYTES).build()
                );
            }
            return res;
        };
        final Storage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = CachedProxySliceReplayTest.proxy(
            upstream, storage, Optional.of(storage), events
        );
        final RequestLine line = new RequestLine(RqMethod.GET, CachedProxySliceReplayTest.ZIP);
        final CompletableFuture<Response> replay = slice.response(
            line, Headers.from(new Header(RequestTrace.HEADER, "held")), Content.EMPTY
        );
        for (int idx = 0; idx < 400 && asked.get() == 0; idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        // The replay holds the single-flight gate of the archive now.
        final CompletableFuture<Response> real = slice.response(
            line, Headers.EMPTY, Content.EMPTY
        );
        TimeUnit.MILLISECONDS.sleep(100L);
        MatcherAssert.assertThat(
            "the client waits behind the replay", asked.get(), new IsEqual<>(1)
        );
        release.complete(null);
        MatcherAssert.assertThat(
            "the replay is answered with what the upstream sent",
            replay.get(60, TimeUnit.SECONDS).body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceReplayTest.BYTES)
        );
        MatcherAssert.assertThat(
            "the client is not left waiting for a file the replay never stores",
            real.get(60, TimeUnit.SECONDS).body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceReplayTest.BYTES)
        );
        final Key key = new Key.From(CachedProxySliceReplayTest.ZIP.substring(1));
        // The commit and the event follow the stream: wait for them.
        for (
            int idx = 0;
            idx < 400 && !(storage.exists(key).join() && events.size() == 1);
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "the client's archive is stored", storage.exists(key).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and announced once, for the client", events.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the client asked the upstream for itself", asked.get(), new IsEqual<>(2)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/example.com/mod/@v/list", "/example.com/mod/@latest"})
    @Timeout(120)
    void replayOfAVersionDocumentStoresNothing(final String path) throws Exception {
        final byte[] document;
        if (path.endsWith("/@latest")) {
            document = "{\"Version\":\"v1.1.0\",\"Time\":\"2024-05-01T10:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8);
        } else {
            document = "v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8);
        }
        final Upstream upstream = new Upstream(path, document);
        final Storage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = CachedProxySliceReplayTest.proxy(
            upstream, storage, Optional.of(storage), events
        );
        final Response replay = CachedProxySliceReplayTest.replay(slice, path);
        MatcherAssert.assertThat(
            "the replay says the document is there",
            replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "with the versions a client would see",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(document)
        );
        CachedProxySliceReplayTest.assertLeftAsFound(storage, events);
        final Response real = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a client's request after it gets the same document",
            real.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(document)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.asked(), new IsEqual<>(2)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        CachedProxySliceReplayTest.ZIP,
        "/example.com/mod/@v/v1.0.0.mod"
    })
    @Timeout(120)
    void replayOfAFileTheUpstreamLacksIsNotFound(final String path) throws Exception {
        final Upstream upstream = new Upstream("/example.com/other/@v/v9.9.9.zip");
        final Storage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice slice = CachedProxySliceReplayTest.proxy(
            upstream, storage, Optional.of(storage), events
        );
        final Response replay = CachedProxySliceReplayTest.replay(slice, path);
        replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the replay gets the status of a client",
            replay.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        CachedProxySliceReplayTest.assertLeftAsFound(storage, events);
    }

    /**
     * The proxy as the repository wiring builds it: the route of
     * {@link GoProxySlice} over a cache that stores what it loads.
     *
     * @param upstream Upstream
     * @param storage Storage of the repository
     * @param verified Storage for the writer that verifies archives
     * @param events Queue of the search index
     * @return Slice
     */
    private static Slice proxy(
        final Slice upstream, final Storage storage, final Optional<Storage> verified,
        final Queue<ProxyArtifactEvent> events
    ) {
        return new GoProxySlice(
            upstream, new FromStorageCache(storage), Optional.of(events), verified,
            "go_proxy", "go-proxy", NoopCooldownService.INSTANCE,
            CachedProxySliceReplayTest.noopInspector()
        );
    }

    /**
     * Ask as the Troubleshoot page does.
     *
     * @param slice Proxy
     * @param path Request path
     * @return The answer, its body not read yet
     * @throws Exception On a failed or late answer
     */
    private static Response replay(final Slice slice, final String path) throws Exception {
        final String trace = RequestTrace.instance().open();
        try {
            return slice.response(
                new RequestLine(RqMethod.GET, path),
                Headers.from(new Header(RequestTrace.HEADER, trace)),
                Content.EMPTY
            ).get(60, TimeUnit.SECONDS);
        } finally {
            RequestTrace.instance().close(trace);
        }
    }

    /**
     * Nothing is in the storage and nothing is queued.
     *
     * @param storage Storage of the repository
     * @param events Queue of the search index
     * @throws InterruptedException If interrupted
     */
    private static void assertLeftAsFound(
        final Storage storage, final Queue<ProxyArtifactEvent> events
    ) throws InterruptedException {
        // A write would come from a callback after the answer: give one
        // that should not exist the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay writes nothing into the storage",
                storage.list(Key.ROOT).join().stream().map(Key::string).toList(),
                new IsEqual<>(List.of())
            );
            MatcherAssert.assertThat(
                "and no event for the search index", events.size(), new IsEqual<>(0)
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
    }

    /**
     * A client's request is served from the upstream, stored, and an
     * archive is announced to the search index.
     *
     * @param slice Proxy
     * @param path Request path
     * @param storage Storage of the repository
     * @param events Queue of the search index
     * @throws Exception On a failed or late answer
     */
    private static void assertClientIsStored(
        final Slice slice, final String path, final Storage storage,
        final Queue<ProxyArtifactEvent> events
    ) throws Exception {
        final Response real = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a client's request after it is served from the upstream",
            real.body().asBytesFuture().get(60, TimeUnit.SECONDS),
            new IsEqual<>(CachedProxySliceReplayTest.BYTES)
        );
        final Key key = new Key.From(path.substring(1));
        final int announced;
        if (path.endsWith(".zip")) {
            announced = 1;
        } else {
            announced = 0;
        }
        // The save and the event follow the stream: wait for them, do
        // not time them.
        for (
            int idx = 0;
            idx < 400 && !(storage.exists(key).join() && events.size() == announced);
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "and stored as before", storage.exists(key).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and announced to the search index as before",
            events.size(), new IsEqual<>(announced)
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

    /**
     * An upstream that has one file.
     *
     * @since 2.2.15
     */
    private static final class Upstream implements Slice {

        /**
         * Path of the file.
         */
        private final String path;

        /**
         * Its bytes.
         */
        private final byte[] bytes;

        /**
         * Number of GET requests for the file.
         */
        private final AtomicInteger gets;

        Upstream(final String path) {
            this(path, CachedProxySliceReplayTest.BYTES);
        }

        Upstream(final String path, final byte[] bytes) {
            this.path = path;
            this.bytes = bytes.clone();
            this.gets = new AtomicInteger();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final Response res;
            if (line.method() == RqMethod.GET && line.uri().getPath().equals(this.path)) {
                this.gets.incrementAndGet();
                res = ResponseBuilder.ok().body(this.bytes).build();
            } else {
                res = ResponseBuilder.notFound().build();
            }
            return CompletableFuture.completedFuture(res);
        }

        int asked() {
            return this.gets.get();
        }
    }
}
