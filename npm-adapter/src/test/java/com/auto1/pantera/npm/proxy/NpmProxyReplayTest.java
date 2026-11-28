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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.misc.PackumentJson;
import com.auto1.pantera.npm.proxy.http.CachedNpmProxySlice;
import com.auto1.pantera.npm.proxy.http.NpmProxySlice;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A Troubleshoot replay asks an npm proxy what a client would get. It
 * must leave the proxy as it found it: a tarball or a packument the
 * upstream has is answered, not stored, no event for the search index is
 * written, no stored content is announced and no refresh is started. A
 * replay that cached the tarball changed what the group index says, and
 * with it the answers of the requests that followed.
 *
 * @since 2.2.15
 */
final class NpmProxyReplayTest {

    /**
     * Client-facing base stamped on every packument request.
     */
    private static final String PREFIX = "https://h/api/npm/npm_proxy";

    /**
     * Abbreviated packument media type.
     */
    private static final String ABBREVIATED = "application/vnd.npm.install-v1+json";

    /**
     * Upstream Last-Modified value.
     */
    private static final String LAST_MODIFIED = "Tue, 24 Mar 2020 12:15:16 GMT";

    /**
     * Directory of the packument temp files.
     */
    @TempDir
    Path tmp;

    /**
     * Executor of the blocking packument work.
     */
    private ExecutorService executor;

    /**
     * Storage of the repository.
     */
    private Storage storage;

    /**
     * Events for the search index.
     */
    private Queue<ProxyArtifactEvent> events;

    /**
     * Tarballs announced as stored.
     */
    private AtomicInteger tarballs;

    /**
     * Packuments announced as stored.
     */
    private AtomicInteger packuments;

    @BeforeEach
    void setUp() {
        final AtomicInteger count = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(
            4,
            runnable -> {
                final Thread thread = new Thread(
                    runnable, "npm-replay-test-" + count.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            }
        );
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.tarballs = new AtomicInteger();
        this.packuments = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        this.executor.shutdownNow();
    }

    @Test
    @Timeout(120)
    void replayOfATarballHandsOnTheBytesAndStoresNothing() throws Exception {
        final String path = "/tgz-replay/-/tgz-replay-1.0.0.tgz";
        final byte[] bytes = "tarball bytes".getBytes(StandardCharsets.UTF_8);
        final Upstream upstream = new Upstream().with(path, bytes);
        final Slice slice = this.proxy(upstream, NoopCooldownMetadataService.INSTANCE);
        final Answer replay = NpmProxyReplayTest.ask(slice, path, Headers.EMPTY, true);
        MatcherAssert.assertThat(
            "the replay says the file is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and hands on the bytes of the upstream", replay.body(), new IsEqual<>(bytes)
        );
        this.assertStays(Map.of(), "the replay stores no tarball and no metadata of it");
        MatcherAssert.assertThat(
            "and writes no event for the search index", this.events.size(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "and announces no stored tarball", this.tarballs.get(), new IsEqual<>(0)
        );
        final Answer client = NpmProxyReplayTest.ask(slice, path, Headers.EMPTY, false);
        MatcherAssert.assertThat(
            "a client's request after it is served from the upstream",
            client.body(), new IsEqual<>(bytes)
        );
        // The commit follows the stream: wait for it, do not time it.
        final Key key = new Key.From(path.substring(1));
        NpmProxyReplayTest.await(
            () -> this.storage.exists(key).join()
                && this.storage.exists(new Key.From(key.string() + ".meta")).join()
                && this.tarballs.get() == 1,
            "the client's tarball is stored and announced as before"
        );
        MatcherAssert.assertThat(
            "and written to the search index as before", this.events.size(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.asked(path), new IsEqual<>(2)
        );
        final Map<String, String> stored = this.stored();
        final Answer cached = NpmProxyReplayTest.ask(slice, path, Headers.EMPTY, true);
        MatcherAssert.assertThat(
            "a replay of a tarball the proxy holds is served from its cache",
            cached.body(), new IsEqual<>(bytes)
        );
        this.assertStays(stored, "which the replay leaves as it is");
        MatcherAssert.assertThat(
            "without asking the upstream", upstream.asked(path), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "and without an event", this.events.size(), new IsEqual<>(1)
        );
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    @Timeout(120)
    void replayOfAPackumentAnswersTheDocumentAndStoresNothing(
        final boolean abbreviated, final boolean cooldown
    ) throws Exception {
        final String pkg = String.format("doc-replay-%s-%s", abbreviated, cooldown);
        final String path = "/" + pkg;
        final Upstream upstream = new Upstream().with(
            path, new SyntheticPackument(pkg, 3, 50).document().getBytes(StandardCharsets.UTF_8)
        );
        final Slice slice = this.proxy(
            upstream,
            cooldown ? new Withheld(Set.of("1.0.2")) : NoopCooldownMetadataService.INSTANCE
        );
        final Headers request = Headers.from(ClientBaseUrl.HEADER, PREFIX);
        if (abbreviated) {
            request.add("Accept", NpmProxyReplayTest.ABBREVIATED);
        }
        final Answer replay = NpmProxyReplayTest.ask(slice, path, request, true);
        MatcherAssert.assertThat(
            "the replay says the packument is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        this.assertStays(Map.of(), "the replay stores no packument");
        MatcherAssert.assertThat(
            "and announces no stored packument", this.packuments.get(), new IsEqual<>(0)
        );
        this.awaitNoTempFiles();
        final Answer client = NpmProxyReplayTest.ask(slice, path, request, false);
        MatcherAssert.assertThat(
            "the replay got the document a client gets",
            new String(replay.body(), StandardCharsets.UTF_8),
            new IsEqual<>(new String(client.body(), StandardCharsets.UTF_8))
        );
        for (final String header : new String[] {"ETag", "Content-Type", "Last-Modified"}) {
            MatcherAssert.assertThat(
                "with the same " + header,
                replay.headers().values(header), new IsEqual<>(client.headers().values(header))
            );
        }
        final JsonNode doc = PackumentJson.MAPPER.readTree(replay.body());
        MatcherAssert.assertThat(
            "its tarball links point at the repository the request addressed",
            doc.at("/versions/1.0.0/dist/tarball").asText(),
            new IsEqual<>(String.format("%s/%s/-/%s-1.0.0.tgz", PREFIX, pkg, pkg))
        );
        MatcherAssert.assertThat(
            "and cooldown filtered it as for a client",
            doc.at("/versions").has("1.0.2"), new IsEqual<>(!cooldown)
        );
        MatcherAssert.assertThat(
            "the abbreviated document is served when it is asked for",
            doc.has("readme"), new IsEqual<>(!abbreviated)
        );
        for (final String file : new String[] {"meta.json", "meta.meta", "meta.abbreviated.json"}) {
            MatcherAssert.assertThat(
                "the client's request stored " + file + " as before",
                this.storage.exists(new Key.From(pkg, file)).join(), new IsEqual<>(true)
            );
        }
        MatcherAssert.assertThat(
            "and announced the stored packument", this.packuments.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            upstream.asked(path), new IsEqual<>(2)
        );
        this.awaitNoTempFiles();
    }

    @Test
    @Timeout(120)
    void replayOfAVersionManifestStoresNothing() throws Exception {
        final String pkg = "manifest-replay";
        final Upstream upstream = new Upstream().with(
            "/" + pkg,
            new SyntheticPackument(pkg, 3, 50).document().getBytes(StandardCharsets.UTF_8)
        );
        final Slice slice = this.proxy(upstream, NoopCooldownMetadataService.INSTANCE);
        final Headers request = Headers.from(ClientBaseUrl.HEADER, PREFIX);
        final Answer replay = NpmProxyReplayTest.ask(slice, "/" + pkg + "/1.0.1", request, true);
        MatcherAssert.assertThat(
            "the replay says the version is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and answers its manifest",
            PackumentJson.MAPPER.readTree(replay.body()).at("/dist/tarball").asText(),
            new IsEqual<>(String.format("%s/%s/-/%s-1.0.1.tgz", PREFIX, pkg, pkg))
        );
        this.assertStays(Map.of(), "the replay stores no packument");
        MatcherAssert.assertThat(
            "and announces no stored packument", this.packuments.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once, as for a client",
            upstream.asked("/" + pkg), new IsEqual<>(1)
        );
        this.awaitNoTempFiles();
        final Answer client = NpmProxyReplayTest.ask(slice, "/" + pkg + "/1.0.1", request, false);
        MatcherAssert.assertThat(
            "a client's request after it gets the same manifest",
            new String(client.body(), StandardCharsets.UTF_8),
            new IsEqual<>(new String(replay.body(), StandardCharsets.UTF_8))
        );
        MatcherAssert.assertThat(
            "and stores the packument as before",
            this.storage.exists(new Key.From(pkg, "meta.json")).join(), new IsEqual<>(true)
        );
    }

    @Test
    @Timeout(120)
    void replayOfAStalePackumentStartsNoRefresh() throws Exception {
        final String pkg = "stale-replay";
        final String path = "/" + pkg;
        final SyntheticPackument packument = new SyntheticPackument(pkg, 3, 50);
        final Upstream upstream = new Upstream().with(
            path, packument.document().getBytes(StandardCharsets.UTF_8)
        );
        new RxNpmProxyStorage(new RxStorageWrapper(this.storage), null, this.executor, this.tmp)
            .save(
                new NpmPackage(
                    pkg, packument.cached(),
                    new NpmPackage.Metadata(LAST_MODIFIED, OffsetDateTime.now().minusDays(2L))
                )
            ).blockingAwait();
        final Map<String, String> stored = this.stored();
        final Slice slice = this.proxy(upstream, NoopCooldownMetadataService.INSTANCE);
        final Headers request = Headers.from(ClientBaseUrl.HEADER, PREFIX);
        final Answer replay = NpmProxyReplayTest.ask(slice, path, request, true);
        MatcherAssert.assertThat(
            "the replay is served from the cache", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        this.assertStays(stored, "the replay refreshes nothing");
        MatcherAssert.assertThat(
            "it does not ask the upstream", upstream.asked(path), new IsEqual<>(0)
        );
        final Answer client = NpmProxyReplayTest.ask(slice, path, request, false);
        MatcherAssert.assertThat(
            "a client's request after it gets the same document",
            new String(client.body(), StandardCharsets.UTF_8),
            new IsEqual<>(new String(replay.body(), StandardCharsets.UTF_8))
        );
        NpmProxyReplayTest.await(
            () -> upstream.asked(path) == 1 && this.packuments.get() == 1,
            "the client's request refreshes the stale packument as before"
        );
    }

    @Test
    @Timeout(120)
    void replayOfAPackageTheUpstreamDoesNotHaveIsNotFound() throws Exception {
        final Slice slice = this.proxy(new Upstream(), NoopCooldownMetadataService.INSTANCE);
        MatcherAssert.assertThat(
            "the replay answers what a client gets",
            NpmProxyReplayTest.ask(slice, "/missing-replay", Headers.EMPTY, true).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        this.assertStays(Map.of(), "and stores nothing");
    }

    @Test
    @Timeout(120)
    void replayOfAnInvalidPackumentFailsAsForAClient() throws Exception {
        final String path = "/invalid-replay";
        final Slice slice = this.proxy(
            new Upstream().with(
                path, "{\"name\":\"invalid-replay\",\"vers".getBytes(StandardCharsets.UTF_8)
            ),
            NoopCooldownMetadataService.INSTANCE
        );
        final Answer replay = NpmProxyReplayTest.ask(slice, path, Headers.EMPTY, true);
        this.awaitNoTempFiles();
        MatcherAssert.assertThat(
            "the replay answers what a client gets",
            replay.status(),
            new IsEqual<>(NpmProxyReplayTest.ask(slice, path, Headers.EMPTY, false).status())
        );
        MatcherAssert.assertThat(
            "an upstream failure", replay.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        this.assertStays(Map.of(), "and stores nothing");
    }

    /**
     * The proxy as {@code NpmProxyAdapter} wires it for one remote.
     *
     * @param upstream Upstream registry
     * @param cooldown Cooldown filter of the served packuments
     * @return Repository slice
     */
    private Slice proxy(final Upstream upstream, final CooldownMetadataService cooldown) {
        final NpmProxy npm = new NpmProxy(
            new RxNpmProxyStorage(new RxStorageWrapper(this.storage), null, this.executor, this.tmp),
            new HttpNpmRemote(upstream, this.executor, this.tmp),
            NpmProxy.DEFAULT_METADATA_TTL,
            path -> this.tarballs.incrementAndGet(),
            name -> this.packuments.incrementAndGet(),
            null
        );
        return new CachedNpmProxySlice(
            new NpmProxySlice(
                "", npm, Optional.of(this.events), "npm-proxy-replay", "npm-proxy",
                NoopCooldownService.INSTANCE, cooldown, upstream
            ),
            Optional.of(this.storage), "npm-proxy-replay", "https://registry.npmjs.org", "npm-proxy"
        );
    }

    /**
     * Everything the repository stores, with its content.
     *
     * @return SHA-256 of the bytes by key
     * @throws NoSuchAlgorithmException Never
     */
    private Map<String, String> stored() throws NoSuchAlgorithmException {
        final Map<String, String> all = new TreeMap<>();
        for (final Key key : this.storage.list(Key.ROOT).join()) {
            all.put(
                key.string(),
                HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                        this.storage.value(key).thenCompose(Content::asBytesFuture).join()
                    )
                )
            );
        }
        return all;
    }

    /**
     * Assert that the storage holds what it held. A write would come from
     * a callback after the answer: one that should not exist gets the time
     * to show itself.
     *
     * @param expected What the storage held
     * @param reason What it proves
     * @throws Exception When interrupted
     */
    private void assertStays(final Map<String, String> expected, final String reason)
        throws Exception {
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(reason, this.stored(), new IsEqual<>(expected));
            TimeUnit.MILLISECONDS.sleep(15L);
        }
    }

    /**
     * Wait until every packument temp file is gone: what a replay fetched
     * is deleted when its answer has been read.
     *
     * @throws Exception On timeout
     */
    private void awaitNoTempFiles() throws Exception {
        NpmProxyReplayTest.await(
            () -> {
                try (Stream<Path> files = Files.list(this.tmp)) {
                    return files.findAny().isEmpty();
                } catch (final IOException ex) {
                    throw new IllegalStateException(ex);
                }
            },
            "no packument temp file remains"
        );
    }

    /**
     * Ask the repository and read the answer.
     *
     * @param slice Repository slice
     * @param path Request path
     * @param headers Request headers
     * @param replay Whether the request is a Troubleshoot replay
     * @return Answer with its body
     * @throws Exception On failure
     */
    private static Answer ask(
        final Slice slice, final String path, final Headers headers, final boolean replay
    ) throws Exception {
        final Headers request = headers.copy();
        final String trace = RequestTrace.instance().open();
        if (replay) {
            request.add(new Header(RequestTrace.HEADER, trace));
        }
        try {
            final Response response = slice.response(
                new RequestLine(RqMethod.GET, path), request, Content.EMPTY
            ).get(60, TimeUnit.SECONDS);
            return new Answer(
                response.status(), response.headers(),
                response.body().asBytesFuture().get(60, TimeUnit.SECONDS)
            );
        } finally {
            RequestTrace.instance().close(trace);
        }
    }

    /**
     * Wait for a state that follows the answer; the wait is not a bound
     * on latency.
     *
     * @param condition State waited for
     * @param what What is waited for
     * @throws Exception On timeout
     */
    private static void await(final BooleanSupplier condition, final String what)
        throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting: " + what);
            }
            TimeUnit.MILLISECONDS.sleep(10L);
        }
    }

    /**
     * Answer of the repository, read to its end.
     *
     * @param status Status
     * @param headers Headers
     * @param body Body bytes
     */
    private record Answer(RsStatus status, Headers headers, byte[] body) {
    }

    /**
     * Upstream registry that counts what it is asked for.
     */
    private static final class Upstream implements Slice {

        /**
         * Body by path.
         */
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();

        /**
         * Requests by path.
         */
        private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            this.requests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
            final byte[] file = this.files.get(path);
            final Response response;
            if (file == null) {
                response = ResponseBuilder.notFound().build();
            } else {
                response = ResponseBuilder.ok()
                    .header("Last-Modified", NpmProxyReplayTest.LAST_MODIFIED)
                    .body(file)
                    .build();
            }
            return CompletableFuture.completedFuture(response);
        }

        /**
         * Add a file.
         *
         * @param path Its path
         * @param body Its bytes
         * @return This upstream
         */
        Upstream with(final String path, final byte[] body) {
            this.files.put(path, body);
            return this;
        }

        /**
         * How often a path was asked for.
         *
         * @param path The path
         * @return Number of requests
         */
        int asked(final String path) {
            final AtomicInteger count = this.requests.get(path);
            return count == null ? 0 : count.get();
        }
    }

    /**
     * Cooldown that withholds a fixed set of versions, with the filter
     * rules of npm.
     */
    private static final class Withheld implements CooldownMetadataService {

        /**
         * Versions to withhold.
         */
        private final Set<String> blocked;

        Withheld(final Set<String> blocked) {
            this.blocked = blocked;
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType,
            final String repoName,
            final String packageName,
            final byte[] rawMetadata,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter
        ) {
            try {
                return CompletableFuture.completedFuture(
                    rewriter.rewrite(filter.filter(parser.parse(rawMetadata), this.blocked))
                );
            } catch (final Exception ex) {
                throw new CompletionException(ex);
            }
        }

        @Override
        public void invalidate(
            final String repoType, final String repoName, final String packageName
        ) {
            // Nothing is cached.
        }

        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            // Nothing is cached.
        }

        @Override
        public void clearAll() {
            // Nothing is cached.
        }

        @Override
        public String stats() {
            return "Withheld";
        }
    }
}
