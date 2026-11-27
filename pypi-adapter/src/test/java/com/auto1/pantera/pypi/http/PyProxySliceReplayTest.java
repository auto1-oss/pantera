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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.RequestTrace;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Troubleshoot replay asks a PyPI proxy what a client would get. It must
 * leave the proxy as it found it: a file or an index page the upstream has
 * is answered, not stored, no event for the search index is written, a
 * stale page is not refreshed and the links of a page are not remembered.
 * The proxy under test is built the way the server builds it:
 * {@link CachedPyProxySlice} over {@link PyProxySlice}, both on one storage.
 *
 * @since 2.2.15
 */
final class PyProxySliceReplayTest {

    /**
     * Repository name. No other test uses it: the negative cache is shared.
     */
    private static final String REPO = "pypi_replay";

    /**
     * Project page as the proxy asks the upstream for it.
     */
    private static final String UPSTREAM_PAGE = "/simple/example-pkg/";

    /**
     * Wheel of the project.
     */
    private static final String WHEEL = "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl";

    /**
     * Storage key of the project page: the proxy keeps the PEP 691 document.
     */
    private static final Key PAGE_KEY = new Key.From("example-pkg.json");

    /**
     * Repository storage.
     */
    private Storage storage;

    /**
     * Artifact events of the proxy.
     */
    private Queue<ProxyArtifactEvent> events;

    /**
     * Upstream.
     */
    private FakeUpstream upstream;

    /**
     * Proxy under test.
     */
    private Slice proxy;

    @BeforeEach
    void init() {
        this.storage = new InMemoryStorage();
        this.events = new ConcurrentLinkedQueue<>();
        this.upstream = new FakeUpstream();
        this.proxy = PyProxySliceReplayTest.proxy(
            PyProxySliceReplayTest.REPO, this.upstream, this.storage, this.events
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,1",
        "/packages/aa/bb/example_pkg-1.0.0.tar.gz,1",
        "/packages/aa/bb/example_pkg-1.0.0.zip,1",
        "/packages/aa/bb/example_pkg-1.0.0.egg,1",
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl.metadata,0"
    })
    @Timeout(120)
    void replayOfAFileHandsItOnAndStoresNothing(final String path, final int published)
        throws Exception {
        final byte[] bytes = "distribution bytes".getBytes(StandardCharsets.UTF_8);
        this.upstream.serve(path, bytes);
        final Answer replay = this.replay(path, "*/*");
        MatcherAssert.assertThat(
            "the replay says the file is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "and hands on what the upstream sent", replay.body(), new IsEqual<>(bytes)
        );
        this.assertNothingStored();
        MatcherAssert.assertThat(
            "the replay writes no event for the search index",
            this.events.size(), new IsEqual<>(0)
        );
        final Answer client = this.get(path, PyProxySliceReplayTest.accept("*/*"));
        MatcherAssert.assertThat(
            "a client's request after it is served from the upstream",
            client.body(), new IsEqual<>(bytes)
        );
        final Key key = new Key.From(path.substring(1));
        this.awaitStored(key);
        MatcherAssert.assertThat(
            "and stored as before", this.storage.exists(key).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "and announced to the search index as before",
            this.events.size(), new IsEqual<>(published)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            this.upstream.asked(path), new IsEqual<>(2)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/vnd.pypi.simple.v1+json"})
    @Timeout(120)
    void replayOfAnIndexPageServesThePageAndStoresNothing(final String accept)
        throws Exception {
        this.upstream.serve(
            PyProxySliceReplayTest.UPSTREAM_PAGE,
            PyProxySliceReplayTest.page("https://files.pythonhosted.org", "1.0.0")
        );
        final Answer replay = this.replay("/example-pkg/", accept);
        MatcherAssert.assertThat(
            "the replay says the page is there", replay.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "with the links a client gets: they point at the proxy",
            new String(replay.body(), StandardCharsets.UTF_8),
            new StringContains(
                String.format("/%s%s", PyProxySliceReplayTest.REPO, PyProxySliceReplayTest.WHEEL)
            )
        );
        this.assertNothingStored();
        final Answer client = this.get("/example-pkg/", PyProxySliceReplayTest.accept(accept));
        MatcherAssert.assertThat(
            "a client's request after it gets the page the replay showed",
            client.body(), new IsEqual<>(replay.body())
        );
        this.awaitStored(PyProxySliceReplayTest.PAGE_KEY);
        MatcherAssert.assertThat(
            "and the page is stored as before",
            this.storage.exists(PyProxySliceReplayTest.PAGE_KEY).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the upstream was asked once for the replay and once for the client",
            this.upstream.asked(PyProxySliceReplayTest.UPSTREAM_PAGE), new IsEqual<>(2)
        );
    }

    @Test
    @Timeout(120)
    void replayOfAStaleIndexPageServesItAndStartsNoRefresh() throws Exception {
        // The in-memory storage keeps no modification time, and a page
        // without one is stale: a client's request for it starts a refresh.
        final byte[] stale = String.format(
            "{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"example-pkg\",\"files\":[{"
                + "\"filename\":\"example_pkg-0.9.0-py3-none-any.whl\","
                + "\"url\":\"/%s/packages/aa/bb/example_pkg-0.9.0-py3-none-any.whl\","
                + "\"hashes\":{},\"upload-time\":\"2020-01-01T00:00:00Z\"}]}",
            PyProxySliceReplayTest.REPO
        ).getBytes(StandardCharsets.UTF_8);
        this.storage.save(PyProxySliceReplayTest.PAGE_KEY, new Content.From(stale)).join();
        this.upstream.serve(
            PyProxySliceReplayTest.UPSTREAM_PAGE,
            PyProxySliceReplayTest.page("https://files.pythonhosted.org", "1.0.0")
        );
        final Answer replay = this.replay("/example-pkg/", "text/html");
        MatcherAssert.assertThat(
            "the replay serves the page the proxy holds",
            new String(replay.body(), StandardCharsets.UTF_8),
            new StringContains("example_pkg-0.9.0-py3-none-any.whl")
        );
        // A refresh would run after the answer: give one that should not
        // exist the time to show itself.
        for (int idx = 0; idx < 20; idx += 1) {
            MatcherAssert.assertThat(
                "the replay does not ask the upstream for a newer page",
                this.upstream.asked(PyProxySliceReplayTest.UPSTREAM_PAGE), new IsEqual<>(0)
            );
            MatcherAssert.assertThat(
                "and leaves the stored page as it was",
                this.stored(PyProxySliceReplayTest.PAGE_KEY), new IsEqual<>(stale)
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
        final Answer client = this.get(
            "/example-pkg/", PyProxySliceReplayTest.accept("text/html")
        );
        MatcherAssert.assertThat(
            "a client's request after it gets the stale page as well",
            new String(client.body(), StandardCharsets.UTF_8),
            new StringContains("example_pkg-0.9.0-py3-none-any.whl")
        );
        // The refresh follows the answer: wait for it, do not time it.
        for (
            int idx = 0;
            idx < 400 && java.util.Arrays.equals(
                this.stored(PyProxySliceReplayTest.PAGE_KEY), stale
            );
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "and refreshes the stored page as before",
            new String(this.stored(PyProxySliceReplayTest.PAGE_KEY), StandardCharsets.UTF_8),
            new StringContains("example_pkg-1.0.0-py3-none-any.whl")
        );
    }

    @Test
    @Timeout(120)
    void replayOfAnIndexPageDoesNotTeachTheProxyWhereTheFilesAre() throws Exception {
        // The upstream keeps its files on another host. The proxy learns
        // that host from the links of a page it serves to a client.
        final byte[] bytes = "wheel on the mirror".getBytes(StandardCharsets.UTF_8);
        this.upstream.serve(
            PyProxySliceReplayTest.UPSTREAM_PAGE,
            PyProxySliceReplayTest.page("https://mirror.example/root", "1.0.0")
        );
        this.upstream.serve("/root" + PyProxySliceReplayTest.WHEEL, bytes);
        this.replay("/example-pkg/", "text/html");
        MatcherAssert.assertThat(
            "after a replay of the page a client's file request is answered as before it",
            this.get(PyProxySliceReplayTest.WHEEL, PyProxySliceReplayTest.accept("*/*")).status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
        this.get("/example-pkg/", PyProxySliceReplayTest.accept("text/html"));
        MatcherAssert.assertThat(
            "after a client's request for the page the file comes from the host it names",
            this.get(PyProxySliceReplayTest.WHEEL, PyProxySliceReplayTest.accept("*/*")).body(),
            new IsEqual<>(bytes)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/packages/aa/bb/absent_pkg-1.0.0-py3-none-any.whl,NOT_FOUND",
        "/packages/aa/bb/absent_pkg-1.0.0.egg,NOT_FOUND",
        "/packages/aa/bb/absent_pkg-1.0.0-py3-none-any.whl.metadata,NOT_FOUND",
        "/absent-pkg/,NOT_FOUND",
        "/packages/aa/bb/broken_pkg-1.0.0-py3-none-any.whl,BAD_GATEWAY",
        "/packages/aa/bb/broken_pkg-1.0.0.egg,BAD_GATEWAY",
        "/broken-pkg/,BAD_GATEWAY"
    })
    @Timeout(120)
    void replayOfWhatTheUpstreamDoesNotGiveEndsAsForAClient(
        final String path, final RsStatus expected
    ) throws Exception {
        this.upstream.fail("/packages/aa/bb/broken_pkg-1.0.0-py3-none-any.whl");
        this.upstream.fail("/packages/aa/bb/broken_pkg-1.0.0.egg");
        this.upstream.fail("/simple/broken-pkg/");
        final RsStatus replay = this.replay(path, "*/*").status();
        this.assertNothingStored();
        // The client asks a proxy of its own: the replay may have left
        // nothing in this one, and that is not what is compared here.
        final RsStatus client = PyProxySliceReplayTest.get(
            PyProxySliceReplayTest.proxy(
                "pypi_replay_client", this.upstream, new InMemoryStorage(),
                new ConcurrentLinkedQueue<>()
            ),
            path, PyProxySliceReplayTest.accept("*/*")
        ).status();
        MatcherAssert.assertThat(
            "a client is answered with the upstream's outcome",
            client, new IsEqual<>(expected)
        );
        MatcherAssert.assertThat(
            "and the replay ends with the status a client gets",
            replay, new IsEqual<>(client)
        );
    }

    @Test
    @Timeout(120)
    void replayOfAnIndexPageWithoutFilesEndsAsForAClient() throws Exception {
        // The proxy keeps no page without links and answers it as a miss:
        // 404, not a failure of the upstream.
        this.upstream.serve(
            "/simple/empty-pkg/",
            "{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"empty-pkg\",\"files\":[]}"
                .getBytes(StandardCharsets.UTF_8)
        );
        final RsStatus replay = this.replay("/empty-pkg/", "text/html").status();
        this.assertNothingStored();
        final RsStatus client = PyProxySliceReplayTest.get(
            PyProxySliceReplayTest.proxy(
                "pypi_replay_client", this.upstream, new InMemoryStorage(),
                new ConcurrentLinkedQueue<>()
            ),
            "/empty-pkg/", PyProxySliceReplayTest.accept("text/html")
        ).status();
        MatcherAssert.assertThat(
            "the replay ends with the status a client gets", replay, new IsEqual<>(client)
        );
        MatcherAssert.assertThat(
            "which is not found", replay, new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    /**
     * Replay a request as the Troubleshoot page does.
     * @param path Request path
     * @param accept Accept header value
     * @return What the proxy answered
     * @throws Exception On failure or timeout
     */
    private Answer replay(final String path, final String accept) throws Exception {
        final String trace = RequestTrace.instance().open();
        try {
            return this.get(
                path,
                PyProxySliceReplayTest.accept(accept).add(new Header(RequestTrace.HEADER, trace))
            );
        } finally {
            RequestTrace.instance().close(trace);
        }
    }

    /**
     * Send a GET to the proxy under test and read the whole answer.
     * @param path Request path
     * @param headers Request headers
     * @return What the proxy answered
     * @throws Exception On failure or timeout
     */
    private Answer get(final String path, final Headers headers) throws Exception {
        return PyProxySliceReplayTest.get(this.proxy, path, headers);
    }

    /**
     * Send a GET and read the whole answer.
     * @param slice Proxy
     * @param path Request path
     * @param headers Request headers
     * @return What the proxy answered
     * @throws Exception On failure or timeout
     */
    private static Answer get(final Slice slice, final String path, final Headers headers)
        throws Exception {
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), headers, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        return new Answer(
            response.status(), response.body().asBytesFuture().get(60, TimeUnit.SECONDS)
        );
    }

    /**
     * A PyPI proxy as the server builds it: the caching slice over the
     * routed proxy slice, both on one storage.
     * @param name Repository name
     * @param upstream Upstream
     * @param storage Repository storage
     * @param events Artifact events queue
     * @return Proxy
     */
    private static Slice proxy(
        final String name, final ClientSlices upstream, final Storage storage,
        final Queue<ProxyArtifactEvent> events
    ) {
        return new CachedPyProxySlice(
            new PyProxySlice(
                upstream, URI.create("https://pypi.example/simple"),
                Authenticator.ANONYMOUS, storage, Optional.of(events),
                name, "pypi-proxy", NoopCooldownService.INSTANCE
            ),
            Optional.of(storage), Duration.ofHours(24), true,
            name, "https://pypi.example/simple", "pypi-proxy"
        );
    }

    /**
     * The storage stays empty. A write would come from a callback after the
     * answer: one that should not exist gets the time to show itself.
     * @throws InterruptedException When interrupted
     */
    private void assertNothingStored() throws InterruptedException {
        for (int idx = 0; idx < 20; idx += 1) {
            final Collection<Key> keys = this.storage.list(Key.ROOT).join();
            MatcherAssert.assertThat(
                "the replay stores nothing", List.copyOf(keys), new IsEqual<>(List.of())
            );
            TimeUnit.MILLISECONDS.sleep(15L);
        }
    }

    /**
     * Wait until a key is stored. The save follows the stream: it is waited
     * for, not timed.
     * @param key Storage key
     * @throws InterruptedException When interrupted
     */
    private void awaitStored(final Key key) throws InterruptedException {
        for (int idx = 0; idx < 400 && !this.storage.exists(key).join(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }

    /**
     * Stored bytes.
     * @param key Storage key
     * @return Bytes
     */
    private byte[] stored(final Key key) {
        return this.storage.value(key).join().asBytes();
    }

    /**
     * Request headers of a client.
     * @param accept Accept header value
     * @return Headers
     */
    private static Headers accept(final String accept) {
        return Headers.from(new Header("Accept", accept));
    }

    /**
     * PEP 691 project page of the upstream with one wheel.
     * @param host Scheme, host and path prefix the wheel link starts with
     * @param version Version of the wheel
     * @return Document
     */
    private static byte[] page(final String host, final String version) {
        return String.format(
            "{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"example-pkg\",\"files\":[{"
                + "\"filename\":\"example_pkg-%2$s-py3-none-any.whl\","
                + "\"url\":\"%1$s/packages/aa/bb/example_pkg-%2$s-py3-none-any.whl\","
                + "\"hashes\":{},\"upload-time\":\"2020-01-01T00:00:00Z\"}]}",
            host, version
        ).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * What the proxy answered.
     * @param status Status
     * @param body Body
     */
    private record Answer(RsStatus status, byte[] body) {
    }

    /**
     * Upstream that serves the files it was given, whatever the host, and
     * counts the requests per path.
     */
    private static final class FakeUpstream implements ClientSlices {

        /**
         * Bodies by path.
         */
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();

        /**
         * Paths answered with a server error.
         */
        private final Collection<String> broken = new ConcurrentLinkedQueue<>();

        /**
         * Paths asked for, in order.
         */
        private final Queue<String> requests = new ConcurrentLinkedQueue<>();

        @Override
        public Slice http(final String host) {
            return this.slice();
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice();
        }

        @Override
        public Slice https(final String host) {
            return this.slice();
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice();
        }

        /**
         * Serve a file.
         * @param path Path at the upstream
         * @param body Body
         */
        void serve(final String path, final byte[] body) {
            this.files.put(path, body);
        }

        /**
         * Answer a path with a server error.
         * @param path Path at the upstream
         */
        void fail(final String path) {
            this.broken.add(path);
        }

        /**
         * How often a path was asked for.
         * @param path Path at the upstream
         * @return Number of requests
         */
        int asked(final String path) {
            return (int) this.requests.stream().filter(path::equals).count();
        }

        private Slice slice() {
            return (line, headers, body) -> {
                final String path = line.uri().getPath();
                this.requests.add(path);
                final byte[] found = this.files.get(path);
                final Response res;
                if (this.broken.contains(path)) {
                    res = ResponseBuilder.internalError().build();
                } else if (found == null) {
                    res = ResponseBuilder.notFound().build();
                } else {
                    res = ResponseBuilder.ok().body(found).build();
                }
                return CompletableFuture.completedFuture(res);
            };
        }
    }
}
