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
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.fs.FileStorage;
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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import io.reactivex.Flowable;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * A metadata lookup the cache cannot answer stores the package document
 * once and nothing else: the document the later requests are served, and
 * that the dist download resolves the upstream link from. Each storage
 * write is a request to the storage, a PUT on S3. The lookup wrote the
 * root {@code packages.json} as well, which no request of a proxy reads
 * (the proxy answers its root itself), and the package document twice
 * under the same key.
 *
 * <p>The proxy is wired as the {@code php-proxy} repository wires it: the
 * repository and the metadata cache work on the same storage. The slice
 * keeps the document itself, so one whose cache stores nothing keeps it
 * too.</p>
 *
 * @since 2.2.15
 */
final class ComposerProxyMetadataWritesTest {

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
     * Write of the package document.
     */
    private static final String META_SAVE = "save acme/widget.json";

    /**
     * Path of the dist archive at the upstream.
     */
    private static final String ZIP_PATH = "/files/widget-1.0.0.zip";

    /**
     * Path of the dist archive at the proxy.
     */
    private static final String DIST_PATH = "/dist/acme/widget/1.0.0.zip";

    /**
     * Package metadata as the upstream sends it.
     */
    private static final byte[] META = (
        "{\"packages\":{\"acme/widget\":[{\"name\":\"acme/widget\","
            + "\"version\":\"1.0.0\",\"time\":\"2020-01-01T00:00:00+00:00\","
            + "\"dist\":{\"type\":\"zip\",\"url\":\"" + REMOTE + ZIP_PATH + "\"}}]}}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Package metadata as the proxy keeps it, with a dist link the
     * upstream no longer has.
     */
    private static final byte[] CACHED = (
        "{\"packages\":{\"acme/widget\":{\"1.0.0\":{\"name\":\"acme/widget\","
            + "\"version\":\"1.0.0\",\"dist\":{\"type\":\"zip\","
            + "\"original_url\":\"" + REMOTE + "/files/old.zip\","
            + "\"url\":\"" + BASE + DIST_PATH + "\"}}}}}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Dist archive.
     */
    private static final byte[] ZIP = "0123456789-zip-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(120)
    void missStoresThePackageDocumentOnceAndLaterRequestsAreServedFromIt(
        @TempDir final Path dir
    ) throws Exception {
        final Upstream upstream = new Upstream().with(META_PATH, META).with(ZIP_PATH, ZIP);
        // A file storage dates what it holds, so the stored document is fresh.
        final Writes storage = new Writes(new FileStorage(dir));
        final Slice proxy = ComposerProxyMetadataWritesTest.proxy(upstream, storage);
        final Response miss = ComposerProxyMetadataWritesTest.get(proxy, META_PATH);
        MatcherAssert.assertThat(
            "the miss is answered", miss.status(), new IsEqual<>(RsStatus.OK)
        );
        final JsonObject served = ComposerProxyMetadataWritesTest.dist(miss);
        MatcherAssert.assertThat(
            "with the dist link pointing at the proxy",
            served.getString("url"), new IsEqual<>(BASE + DIST_PATH)
        );
        MatcherAssert.assertThat(
            "the miss stores the package document once and nothing else",
            ComposerProxyMetadataWritesTest.settled(storage), new IsEqual<>(List.of(META_SAVE))
        );
        MatcherAssert.assertThat(
            "a later request is served the stored document",
            ComposerProxyMetadataWritesTest.dist(
                ComposerProxyMetadataWritesTest.get(proxy, META_PATH)
            ),
            new IsEqual<>(served)
        );
        MatcherAssert.assertThat(
            "without asking the upstream again", upstream.asked(META_PATH), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the dist download finds the upstream link in the stored document",
            ComposerProxyMetadataWritesTest.get(proxy, DIST_PATH).body().asBytesFuture()
                .get(60, TimeUnit.SECONDS),
            new IsEqual<>(ZIP)
        );
        // The commit follows the stream: wait for it, do not time it.
        MatcherAssert.assertThat(
            "and keeps the archive",
            ComposerProxyMetadataWritesTest.eventually(
                () -> storage.exists(new Key.From("dist/acme/widget/1.0.0.zip")).join()
            ),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(120)
    void proxyWhoseCacheStoresNothingStillKeepsThePackageDocument(
        @TempDir final Path dir
    ) throws Exception {
        final Upstream upstream = new Upstream().with(META_PATH, META).with(ZIP_PATH, ZIP);
        final Writes storage = new Writes(new FileStorage(dir));
        final Slice proxy = ComposerProxyMetadataWritesTest.proxy(upstream, storage, Cache.NOP);
        ComposerProxyMetadataWritesTest.get(proxy, META_PATH).body().asBytesFuture()
            .get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the miss stores the package document once",
            ComposerProxyMetadataWritesTest.settled(storage), new IsEqual<>(List.of(META_SAVE))
        );
        MatcherAssert.assertThat(
            "the dist download finds the upstream link in it",
            ComposerProxyMetadataWritesTest.get(proxy, DIST_PATH).body().asBytesFuture()
                .get(60, TimeUnit.SECONDS),
            new IsEqual<>(ZIP)
        );
        // The commit follows the stream: wait for it, do not time it.
        MatcherAssert.assertThat(
            "and keeps the archive",
            ComposerProxyMetadataWritesTest.eventually(
                () -> storage.exists(new Key.From("dist/acme/widget/1.0.0.zip")).join()
            ),
            new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(120)
    void refreshOfStaleMetadataStoresThePackageDocumentOnce() throws Exception {
        final Upstream upstream = new Upstream().with(META_PATH, META);
        final InMemoryStorage mem = new InMemoryStorage();
        mem.save(new Key.From("acme/widget.json"), new Content.From(CACHED)).join();
        final Writes storage = new Writes(new Aged(mem, Duration.ofHours(13)));
        final Slice proxy = ComposerProxyMetadataWritesTest.proxy(upstream, storage);
        MatcherAssert.assertThat(
            "the stale document is served",
            ComposerProxyMetadataWritesTest.dist(
                ComposerProxyMetadataWritesTest.get(proxy, META_PATH)
            ).getString("original_url"),
            new IsEqual<>(REMOTE + "/files/old.zip")
        );
        MatcherAssert.assertThat(
            "and refreshed in the background",
            ComposerProxyMetadataWritesTest.eventually(
                () -> storage.written().contains(META_SAVE)
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the refresh stores the package document once and nothing else",
            ComposerProxyMetadataWritesTest.settled(storage), new IsEqual<>(List.of(META_SAVE))
        );
        MatcherAssert.assertThat(
            "the stored document carries the upstream's dist link",
            ComposerProxyMetadataWritesTest.dist(
                ComposerProxyMetadataWritesTest.get(proxy, META_PATH)
            ).getString("original_url"),
            new IsEqual<>(REMOTE + ZIP_PATH)
        );
    }

    /**
     * Send a client's GET.
     *
     * @param slice Proxy
     * @param path Request path
     * @return Answer of the proxy
     * @throws Exception On failure
     */
    private static Response get(final Slice slice, final String path) throws Exception {
        return slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
    }

    /**
     * The writes so far, once no new one has shown itself for a while: a
     * write may come from a callback after the answer.
     *
     * @param storage Storage of the proxy
     * @return Operation and key of each write
     * @throws InterruptedException If interrupted
     */
    private static List<String> settled(final Writes storage) throws InterruptedException {
        List<String> seen = storage.written();
        int quiet = 0;
        while (quiet < 20) {
            TimeUnit.MILLISECONDS.sleep(15L);
            final List<String> now = storage.written();
            if (now.equals(seen)) {
                quiet += 1;
            } else {
                seen = now;
                quiet = 0;
            }
        }
        return seen;
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
     * The proxy as the {@code php-proxy} repository wires it
     * ({@code ComposerProxy} in pantera-main): repository and metadata
     * cache on the same storage.
     *
     * @param upstream Upstream
     * @param storage Storage of the proxy
     * @return Proxy slice
     */
    private static Slice proxy(final Slice upstream, final Storage storage) {
        return ComposerProxyMetadataWritesTest.proxy(
            upstream, storage, new ComposerStorageCache(new AstoRepository(storage))
        );
    }

    /**
     * The proxy with the given metadata cache.
     *
     * @param upstream Upstream
     * @param storage Storage of the proxy
     * @param cache Metadata cache
     * @return Proxy slice
     */
    private static Slice proxy(final Slice upstream, final Storage storage, final Cache cache) {
        return new ComposerProxySlice(
            new StaticClients(upstream),
            URI.create(REMOTE),
            new AstoRepository(storage),
            Authenticator.ANONYMOUS,
            cache,
            Optional.of(new ConcurrentLinkedQueue<>()),
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
