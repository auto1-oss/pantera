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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import io.reactivex.Flowable;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Troubleshoot replay asks a Composer proxy what a client would get. It
 * must leave the proxy as it found it: the package metadata and the dist
 * archive the upstream has are answered, not stored, a stale document is
 * not refreshed, and no event for the search index is written. A replay
 * that cached the dist added it to the index under this proxy, and with it
 * changed the answers of the requests that followed.
 *
 * @since 2.2.15
 */
final class ComposerProxyReplayTest {

    /**
     * Upstream of the proxy.
     */
    private static final String REMOTE = "https://repo.packagist.example";

    /**
     * Client-facing base of the proxy.
     */
    private static final String BASE = "http://pantera.example:8080/test_prefix/api/php_proxy";

    /**
     * Path of the package metadata.
     */
    private static final String META_PATH = "/p2/acme/widget.json";

    /**
     * Key the proxy stores the package metadata under.
     */
    private static final Key META_KEY = new Key.From("acme/widget.json");

    /**
     * Path of the dist archive at the upstream.
     */
    private static final String ZIP_PATH = "/files/widget-1.0.0.zip";

    /**
     * Package metadata as the upstream sends it.
     */
    private static final byte[] META = (
        "{\"packages\":{\"acme/widget\":[{\"name\":\"acme/widget\","
            + "\"version\":\"1.0.0\",\"time\":\"2020-01-01T00:00:00+00:00\","
            + "\"dist\":{\"type\":\"zip\",\"url\":\"" + REMOTE + ZIP_PATH + "\"}}]}}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Package metadata as the proxy keeps it: the dist link points at the
     * proxy, the upstream's link is kept beside it.
     */
    private static final byte[] CACHED = (
        "{\"packages\":{\"acme/widget\":{\"1.0.0\":{\"name\":\"acme/widget\","
            + "\"version\":\"1.0.0\",\"dist\":{\"type\":\"zip\","
            + "\"original_url\":\"" + REMOTE + ZIP_PATH + "\","
            + "\"url\":\"" + BASE + "/dist/acme/widget/1.0.0.zip\"}}}}}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Dist archive.
     */
    private static final byte[] ZIP = "0123456789-zip-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(120)
    void replayOfPackageMetadataAnswersTheDocumentAndStoresNothing() throws Exception {
        final Upstream upstream = new Upstream().with(META_PATH, META);
        final Writes storage = new Writes(new InMemoryStorage());
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final Slice proxy = ComposerProxyReplayTest.proxy(upstream, storage, events);
        final Response replay = ComposerProxyReplayTest.replay(proxy, META_PATH);
        MatcherAssert.assertThat(
            "the replay says the package is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        final JsonObject replayed = ComposerProxyReplayTest.dist(replay);
        MatcherAssert.assertThat(
            "the replay gets the document with the dist link pointing at the proxy",
            replayed.getString("url"), new IsEqual<>(BASE + "/dist/acme/widget/1.0.0.zip")
        );
        ComposerProxyReplayTest.staysUnwritten(storage);
        MatcherAssert.assertThat(
            "and writes no event for the search index", events.size(), new IsEqual<>(0)
        );
        final Response real = proxy.response(
            new RequestLine(RqMethod.GET, META_PATH), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a client's request after it gets the same dist entry",
            ComposerProxyReplayTest.dist(real), new IsEqual<>(replayed)
        );
        MatcherAssert.assertThat(
            "and its document is stored as before",
            ComposerProxyReplayTest.eventually(() -> storage.exists(META_KEY).join()),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.asked(META_PATH), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void replayOfStaleMetadataServesTheCachedDocumentAndStartsNoRefresh() throws Exception {
        final Upstream upstream = new Upstream().with(META_PATH, META);
        final InMemoryStorage mem = new InMemoryStorage();
        mem.save(META_KEY, new Content.From(CACHED)).join();
        final Writes storage = new Writes(new Aged(mem, Duration.ofHours(13)));
        final Slice proxy = ComposerProxyReplayTest.proxy(
            upstream, storage, new ConcurrentLinkedQueue<>()
        );
        final Response replay = ComposerProxyReplayTest.replay(proxy, META_PATH);
        MatcherAssert.assertThat(
            "the replay is served the cached document",
            ComposerProxyReplayTest.dist(replay).getString("original_url"),
            new IsEqual<>(REMOTE + ZIP_PATH)
        );
        // The refresh of a stale document runs after the answer: give one
        // that should not exist the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay starts no refresh of the stale document",
                upstream.asked(META_PATH), new IsEqual<>(0)
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        ComposerProxyReplayTest.staysUnwritten(storage);
        proxy.response(
            new RequestLine(RqMethod.GET, META_PATH), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS).body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a client's request after it refreshes the stale document as before",
            ComposerProxyReplayTest.eventually(
                () -> storage.written().contains("save " + META_KEY.string())
            ),
            new IsEqual<>(true)
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    @Timeout(120)
    void replayOfMetadataTheUpstreamDoesNotGiveAnswersAsForAClient(final int code)
        throws Exception {
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.from(RsStatus.byCode(code)).build()
        );
        final Writes storage = new Writes(new InMemoryStorage());
        final Slice proxy = ComposerProxyReplayTest.proxy(
            upstream, storage, new ConcurrentLinkedQueue<>()
        );
        final Response replay = ComposerProxyReplayTest.replay(proxy, META_PATH);
        replay.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        ComposerProxyReplayTest.staysUnwritten(storage);
        final Response real = proxy.response(
            new RequestLine(RqMethod.GET, META_PATH), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        real.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the replay answers the status a client gets",
            replay.status(), new IsEqual<>(real.status())
        );
    }

    @Test
    @Timeout(120)
    void replayOfDistArchiveAnswersItAndStoresNothing() throws Exception {
        final Upstream upstream = new Upstream().with(ZIP_PATH, ZIP);
        final InMemoryStorage mem = new InMemoryStorage();
        mem.save(META_KEY, new Content.From(CACHED)).join();
        final Writes storage = new Writes(mem);
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        ComposerProxyReplayTest.archive(
            ComposerProxyReplayTest.proxy(upstream, storage, events),
            "/dist/acme/widget/1.0.0.zip",
            new Key.From("dist", "acme", "widget", "1.0.0.zip"),
            storage, events
        );
        MatcherAssert.assertThat(
            "a client's download writes its event for the search index as before",
            ComposerProxyReplayTest.eventually(() -> events.size() == 1),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.asked(ZIP_PATH), new IsEqual<>(2)
        );
    }

    /**
     * Replay a request for an archive the proxy does not hold, then send
     * the same request as a client.
     *
     * @param slice Proxy
     * @param path Request path of the archive
     * @param key Key the proxy stores the archive under
     * @param storage Storage of the proxy
     * @param events Events for the search index
     * @throws Exception On failure
     */
    private static void archive(
        final Slice slice, final String path, final Key key, final Writes storage,
        final Queue<ProxyArtifactEvent> events
    ) throws Exception {
        final Response replay = ComposerProxyReplayTest.replay(slice, path);
        MatcherAssert.assertThat(
            "the replay says the archive is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "the replay is handed the archive the upstream has",
            replay.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(ZIP)
        );
        ComposerProxyReplayTest.staysUnwritten(storage);
        MatcherAssert.assertThat(
            "and writes no event for the search index", events.size(), new IsEqual<>(0)
        );
        final Response real = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a client's request after it is served from the upstream",
            real.body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(ZIP)
        );
        // The commit follows the stream: wait for it, do not time it.
        MatcherAssert.assertThat(
            "and stored as before",
            ComposerProxyReplayTest.eventually(() -> storage.exists(key).join()),
            new IsEqual<>(true)
        );
    }

    /**
     * Send a request the way the Troubleshoot page replays it.
     *
     * @param slice Proxy
     * @param path Request path
     * @return Answer of the proxy
     * @throws Exception On failure
     */
    private static Response replay(final Slice slice, final String path) throws Exception {
        final String trace = RequestTrace.instance().open();
        try {
            return slice.response(
                new RequestLine(RqMethod.GET, path),
                Headers.from(RequestTrace.HEADER, trace),
                Content.EMPTY
            ).get(60, TimeUnit.SECONDS);
        } finally {
            RequestTrace.instance().close(trace);
        }
    }

    /**
     * A write would come from a callback after the answer: give one that
     * should not exist the time to show itself.
     *
     * @param storage Storage of the proxy
     * @throws InterruptedException If interrupted
     */
    private static void staysUnwritten(final Writes storage) throws InterruptedException {
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay writes nothing into the storage",
                storage.written(), new IsEqual<>(List.of())
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
    }

    /**
     * Wait for a state that follows the answer.
     *
     * @param state State to wait for
     * @return Whether it was reached
     * @throws InterruptedException If interrupted
     */
    private static boolean eventually(final BooleanSupplier state) throws InterruptedException {
        for (int idx = 0; idx < 400 && !state.getAsBoolean(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        return state.getAsBoolean();
    }

    /**
     * The dist entry of version 1.0.0 in a served metadata document.
     *
     * @param response Answer of the proxy
     * @return Dist object
     * @throws Exception On failure
     */
    private static JsonObject dist(final Response response) throws Exception {
        final String body = new String(
            response.body().asBytesFuture().get(60, TimeUnit.SECONDS), StandardCharsets.UTF_8
        );
        return Json.createReader(new StringReader(body)).readObject()
            .getJsonObject("packages").getJsonObject("acme/widget")
            .getJsonObject("1.0.0").getJsonObject("dist");
    }

    /**
     * The proxy as a repository wires it.
     *
     * @param upstream Upstream
     * @param storage Storage of the proxy
     * @param events Events for the search index
     * @return Proxy slice
     */
    private static Slice proxy(
        final Slice upstream, final Storage storage, final Queue<ProxyArtifactEvent> events
    ) {
        return new ComposerProxySlice(
            new StaticClients(upstream),
            URI.create(REMOTE),
            new AstoRepository(storage),
            Authenticator.ANONYMOUS,
            new ComposerStorageCache(new AstoRepository(storage)),
            Optional.of(events),
            "php_proxy",
            "php-proxy",
            NoopCooldownService.INSTANCE,
            new NoDates(),
            BASE
        );
    }

    /**
     * Upstream that has a fixed set of files and counts the requests for each.
     */
    private static final class Upstream implements Slice {

        /**
         * Files by path.
         */
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();

        /**
         * Requests by path.
         */
        private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

        /**
         * Add a file.
         *
         * @param path Path of the file
         * @param body Its bytes
         * @return This upstream
         */
        Upstream with(final String path, final byte[] body) {
            this.files.put(path, body);
            return this;
        }

        /**
         * Number of requests for a file.
         *
         * @param path Path of the file
         * @return Requests so far
         */
        int asked(final String path) {
            return this.requests.computeIfAbsent(path, ignored -> new AtomicInteger()).get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            final byte[] file = this.files.get(path);
            final Response res;
            if (file == null) {
                res = ResponseBuilder.notFound().build();
            } else {
                this.requests.computeIfAbsent(path, ignored -> new AtomicInteger())
                    .incrementAndGet();
                // Chunked, as an upstream streams a file: no size is declared.
                res = ResponseBuilder.ok().body(
                    new Content.From(Flowable.just(ByteBuffer.wrap(file)))
                ).build();
            }
            return CompletableFuture.completedFuture(res);
        }
    }

    /**
     * Storage that records every write it is asked for.
     */
    private static final class Writes extends Storage.Wrap {

        /**
         * Writes so far.
         */
        private final List<String> log = new CopyOnWriteArrayList<>();

        /**
         * Ctor.
         *
         * @param delegate Storage written to
         */
        Writes(final Storage delegate) {
            super(delegate);
        }

        /**
         * Writes so far.
         *
         * @return Operation and key of each write
         */
        List<String> written() {
            return List.copyOf(this.log);
        }

        @Override
        public CompletableFuture<Void> save(final Key key, final Content content) {
            this.log.add("save " + key.string());
            return super.save(key, content);
        }

        @Override
        public CompletableFuture<Void> move(final Key source, final Key destination) {
            this.log.add("move " + destination.string());
            return super.move(source, destination);
        }

        @Override
        public CompletableFuture<Void> delete(final Key key) {
            this.log.add("delete " + key.string());
            return super.delete(key);
        }
    }

    /**
     * Storage whose metadata reports every entry as last updated some time ago.
     */
    private static final class Aged extends Storage.Wrap {

        /**
         * Age of every entry.
         */
        private final Duration age;

        /**
         * Ctor.
         *
         * @param delegate Storage
         * @param age Age of every entry
         */
        Aged(final Storage delegate, final Duration age) {
            super(delegate);
            this.age = age;
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return super.metadata(key).thenApply(meta -> new Meta() {
                @Override
                public <T> T read(final ReadOperator<T> opr) {
                    final Map<String, String> raw = new HashMap<>(meta.read(Map::copyOf));
                    raw.put("updated-at", Instant.now().minus(Aged.this.age).toString());
                    return opr.take(raw);
                }
            });
        }
    }

    /**
     * Client slices that always return the same slice.
     */
    private static final class StaticClients implements ClientSlices {

        /**
         * The slice.
         */
        private final Slice slice;

        /**
         * Ctor.
         *
         * @param slice The slice
         */
        StaticClients(final Slice slice) {
            this.slice = slice;
        }

        @Override
        public Slice http(final String host) {
            return this.slice;
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice;
        }

        @Override
        public Slice https(final String host) {
            return this.slice;
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice;
        }
    }

    /**
     * Inspector with no release dates and no dependencies.
     */
    private static final class NoDates implements CooldownInspector {
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
    }
}
