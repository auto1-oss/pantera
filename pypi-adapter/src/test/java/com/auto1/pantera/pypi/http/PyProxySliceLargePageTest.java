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
import com.auto1.pantera.asto.cache.StreamThroughCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A project page larger than 10 MB (nightly builds such as {@code tf-nightly}
 * list tens of thousands of files) is served by the PyPI proxy, built as the
 * server builds it, on the first request and from the cache after it, with
 * its links pointing at the proxy. A page above the rewrite bound is stored
 * and served as the upstream sent it, on a cache hit as on the first request.
 *
 * @since 2.2.15
 */
final class PyProxySliceLargePageTest {

    /**
     * Source of repository names: the negative cache is shared.
     */
    private static final AtomicInteger REPOS = new AtomicInteger();

    /**
     * Upstream host of the files.
     */
    private static final String FILES = "https://files.pythonhosted.org";

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/vnd.pypi.simple.v1+json"})
    @Timeout(300)
    void largePageIsServedFromTheCache(final String accept, @TempDir final Path dir)
        throws Exception {
        final Upstream upstream = new Upstream(PyProxySliceLargePageTest.page());
        final FileStorage storage = new FileStorage(dir);
        final Slice proxy = PyProxySliceLargePageTest.proxy(
            upstream, storage, "pypi_large_" + PyProxySliceLargePageTest.REPOS.incrementAndGet()
        );
        final RsStatus first = PyProxySliceLargePageTest.get(proxy, accept).status();
        PyProxySliceLargePageTest.awaitStored(storage);
        final List<RsStatus> statuses = List.of(
            first, PyProxySliceLargePageTest.get(proxy, accept).status()
        );
        MatcherAssert.assertThat(
            "the page is served on the first request and from the cache on the second",
            statuses, new IsEqual<>(List.of(RsStatus.OK, RsStatus.OK))
        );
        MatcherAssert.assertThat(
            "the second request is a cache hit", upstream.asked(), new IsEqual<>(1)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/vnd.pypi.simple.v1+json"})
    @Timeout(300)
    void largePageLinksPointAtTheProxy(final String accept, @TempDir final Path dir)
        throws Exception {
        final String name = "pypi_large_" + PyProxySliceLargePageTest.REPOS.incrementAndGet();
        final Slice proxy = PyProxySliceLargePageTest.proxy(
            new Upstream(PyProxySliceLargePageTest.page()), new FileStorage(dir), name
        );
        final String body = PyProxySliceLargePageTest.get(proxy, accept).body();
        MatcherAssert.assertThat(
            "the files are downloaded through the proxy",
            body.contains(
                String.format("/%s/packages/aa/bb/huge_pkg-1.0.0-py3-none-any.whl", name)
            ),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "not from the upstream's host",
            body.contains(PyProxySliceLargePageTest.FILES), new IsEqual<>(false)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "application/vnd.pypi.simple.v1+json"})
    @Timeout(300)
    void pageAboveTheRewriteBoundIsServedAsStored(
        final String accept, @TempDir final Path dir
    ) throws Exception {
        // A bound of 1 KiB stands for 64 MiB: the page is the same shape.
        final Upstream upstream = new Upstream(PyProxySliceLargePageTest.page(4 * 1024));
        final FileStorage storage = new FileStorage(dir);
        final String name = "pypi_large_" + PyProxySliceLargePageTest.REPOS.incrementAndGet();
        final Slice proxy = new CachedPyProxySlice(
            new ProxySlice(
                upstream, Authenticator.ANONYMOUS,
                new UriClientSlice(upstream, URI.create("https://pypi.example/simple")),
                storage, new StreamThroughCache(storage), Optional.empty(), name, "pypi-proxy",
                NoopCooldownService.INSTANCE,
                new RegistryBackedInspector("pypi-proxy", PublishDateRegistries.instance()),
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                ),
                CacheTimeControl.DEFAULT_TTL, URI.create("https://pypi.example/simple"), 1024
            ),
            Optional.of(storage), Duration.ofHours(24), true,
            name, "https://pypi.example/simple", "pypi-proxy"
        );
        final Answer first = PyProxySliceLargePageTest.get(proxy, accept);
        PyProxySliceLargePageTest.awaitStored(storage);
        final Answer second = PyProxySliceLargePageTest.get(proxy, accept);
        MatcherAssert.assertThat(
            "the page is served on the first request and from the cache on the second",
            List.of(first.status(), second.status()),
            new IsEqual<>(List.of(RsStatus.OK, RsStatus.OK))
        );
        MatcherAssert.assertThat(
            "as the upstream sent it, with its links",
            second.body().contains(PyProxySliceLargePageTest.FILES), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the same on the cache hit as on the first request",
            second.body(), new IsEqual<>(first.body())
        );
        MatcherAssert.assertThat(
            "the second request is a cache hit", upstream.asked(), new IsEqual<>(1)
        );
    }

    /**
     * PEP 691 page of a project with enough files to be larger than 10 MB.
     * @return Page
     */
    private static byte[] page() {
        return PyProxySliceLargePageTest.page(11 * 1024 * 1024);
    }

    /**
     * PEP 691 page of a project with enough files to be at least that large.
     * @param size Size of the page
     * @return Page
     */
    private static byte[] page(final int size) {
        final StringBuilder page = new StringBuilder(size + 1024)
            .append("{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"huge-pkg\",\"files\":[");
        int idx = 0;
        while (page.length() < size) {
            if (idx > 0) {
                page.append(',');
            }
            page.append(
                String.format(
                    "{\"filename\":\"huge_pkg-1.0.%1$d-py3-none-any.whl\","
                        + "\"url\":\"%2$s/packages/aa/bb/huge_pkg-1.0.%1$d-py3-none-any.whl\","
                        + "\"hashes\":{\"sha256\":\"%3$064d\"},"
                        + "\"requires-python\":\">=3.9\","
                        + "\"upload-time\":\"2020-01-01T00:00:00Z\"}",
                    idx, PyProxySliceLargePageTest.FILES, idx
                )
            );
            idx += 1;
        }
        return page.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Send a GET for the project page and read the whole answer.
     * @param proxy Proxy
     * @param accept Accept header value
     * @return Answer
     * @throws Exception On failure or timeout
     */
    private static Answer get(final Slice proxy, final String accept) throws Exception {
        final Response response = proxy.response(
            new RequestLine(RqMethod.GET, "/huge-pkg/"),
            Headers.from(new Header("Accept", accept)),
            Content.EMPTY
        ).get(120, TimeUnit.SECONDS);
        return new Answer(
            response.status(),
            new String(
                response.body().asBytesFuture().get(120, TimeUnit.SECONDS),
                StandardCharsets.UTF_8
            )
        );
    }

    /**
     * A PyPI proxy as the server builds it, on a file storage, which tells
     * a fresh page from a stale one.
     * @param upstream Upstream
     * @param storage Repository storage
     * @param name Repository name
     * @return Proxy
     */
    private static Slice proxy(
        final ClientSlices upstream, final FileStorage storage, final String name
    ) {
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
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
     * Wait until the project page is stored. The proxy saves it after the
     * first answer's body was read: the save is waited for, not timed.
     * @param storage Repository storage
     * @throws InterruptedException When interrupted
     */
    private static void awaitStored(final FileStorage storage) throws InterruptedException {
        final Key key = new Key.From("huge-pkg.json");
        for (int idx = 0; idx < 400 && !storage.exists(key).join(); idx += 1) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
    }

    /**
     * What the proxy answered.
     * @param status Status
     * @param body Body
     */
    private record Answer(RsStatus status, String body) {
    }

    /**
     * Upstream that serves the project page and counts the requests for it.
     */
    private static final class Upstream implements ClientSlices {

        /**
         * Project page.
         */
        private final byte[] page;

        /**
         * Requests for the page.
         */
        private final AtomicInteger requests = new AtomicInteger();

        /**
         * Ctor.
         * @param page Project page
         */
        Upstream(final byte[] page) {
            this.page = page;
        }

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
         * Requests for the page.
         * @return Number of requests
         */
        int asked() {
            return this.requests.get();
        }

        private Slice slice() {
            return (line, headers, body) -> {
                final Response res;
                if ("/simple/huge-pkg/".equals(line.uri().getPath())) {
                    this.requests.incrementAndGet();
                    res = ResponseBuilder.ok()
                        .header("Content-Type", "application/vnd.pypi.simple.v1+json")
                        .body(this.page)
                        .build();
                } else {
                    res = ResponseBuilder.notFound().build();
                }
                return CompletableFuture.completedFuture(res);
            };
        }
    }
}
