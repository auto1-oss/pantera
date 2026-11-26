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
import com.auto1.pantera.asto.Meta;
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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The JSON API ({@code /pypi/<project>/json}) of a PyPI proxy built as the
 * server builds it: the document is fetched from the upstream when the
 * proxy holds none, kept, and served from the proxy afterwards; what the
 * upstream does not have is answered with the upstream's status.
 *
 * @since 2.3.0
 */
final class PyProxySliceJsonApiTest {

    /**
     * Request path of the document.
     */
    private static final String PATH = "/pypi/example-pkg/json";

    /**
     * The document of the upstream.
     */
    private static final byte[] DOCUMENT = String.join(
        "",
        "{\"info\":{\"name\":\"example-pkg\",\"version\":\"1.0.0\"},",
        "\"releases\":{\"1.0.0\":[{\"filename\":\"example_pkg-1.0.0.tar.gz\",",
        "\"upload_time_iso_8601\":\"2020-01-01T00:00:00.000000Z\"}]},\"urls\":[]}"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Repository storage.
     */
    private Storage storage;

    /**
     * Upstream.
     */
    private Upstream upstream;

    /**
     * Proxy under test.
     */
    private Slice proxy;

    @BeforeEach
    void init() {
        this.storage = new Dated(new InMemoryStorage());
        this.upstream = new Upstream();
        this.proxy = new CachedPyProxySlice(
            new PyProxySlice(
                this.upstream, URI.create("https://pypi.example/simple"),
                Authenticator.ANONYMOUS, this.storage,
                Optional.of(new ConcurrentLinkedQueue<>()),
                "pypi_json", "pypi-proxy", NoopCooldownService.INSTANCE
            ),
            Optional.of(this.storage), Duration.ofHours(24), true,
            "pypi_json", "https://pypi.example/simple", "pypi-proxy"
        );
    }

    @Test
    @Timeout(120)
    void fetchesTheDocumentOfAProjectTheProxyDoesNotHoldAndKeepsIt() throws Exception {
        this.upstream.files.put(PyProxySliceJsonApiTest.PATH, PyProxySliceJsonApiTest.DOCUMENT);
        final Response first = this.get();
        MatcherAssert.assertThat(
            "the first request is answered", first.status(), new IsEqual<>(RsStatus.OK)
        );
        final byte[] served = first.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        // The save follows the stream: it is waited for, not timed.
        for (
            int idx = 0;
            idx < 400 && this.storage.list(Key.ROOT).join().isEmpty();
            idx += 1
        ) {
            TimeUnit.MILLISECONDS.sleep(25L);
        }
        MatcherAssert.assertThat(
            "the document is kept",
            this.storage.list(Key.ROOT).join().isEmpty(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the second request gets the same document",
            this.get().body().asBytesFuture().get(60, TimeUnit.SECONDS), new IsEqual<>(served)
        );
        MatcherAssert.assertThat(
            "from the proxy, without a second upstream request",
            this.upstream.asked.get(), new IsEqual<>(1)
        );
    }

    @Test
    @Timeout(120)
    void answersNotFoundForAProjectTheUpstreamDoesNotHave() throws Exception {
        final Response response = this.get();
        response.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        MatcherAssert.assertThat(response.status(), new IsEqual<>(RsStatus.NOT_FOUND));
    }

    /**
     * Ask the proxy for the document as a client.
     * @return Response
     * @throws Exception On failure or timeout
     */
    private Response get() throws Exception {
        return this.proxy.response(
            new RequestLine(RqMethod.GET, PyProxySliceJsonApiTest.PATH),
            Headers.EMPTY, Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
    }

    /**
     * Upstream that serves what it was given and answers 404 otherwise.
     * @since 2.3.0
     */
    private static final class Upstream implements ClientSlices {

        /**
         * Bodies by path.
         */
        private final Map<String, byte[]> files = new ConcurrentHashMap<>();

        /**
         * Number of requests received.
         */
        private final AtomicInteger asked = new AtomicInteger();

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

        private Slice slice() {
            return (line, headers, body) -> {
                this.asked.incrementAndGet();
                final byte[] found = this.files.get(line.uri().getPath());
                final Response res;
                if (found == null) {
                    res = ResponseBuilder.notFound().build();
                } else {
                    res = ResponseBuilder.ok().body(found).build();
                }
                return CompletableFuture.completedFuture(res);
            };
        }
    }

    /**
     * Storage that dates its entries, as a file or S3 storage does: the
     * in-memory one reports no time of the last write, and an entry without
     * one is never fresh.
     * @since 2.3.0
     */
    private static final class Dated extends Storage.Wrap {

        /**
         * Ctor.
         * @param delegate Storage
         */
        Dated(final Storage delegate) {
            super(delegate);
        }

        @Override
        public CompletableFuture<? extends Meta> metadata(final Key key) {
            return super.metadata(key).thenApply(meta -> new Meta() {
                @Override
                public <T> T read(final ReadOperator<T> opr) {
                    final Map<String, String> raw = new HashMap<>(meta.read(Map::copyOf));
                    raw.put("updated-at", Instant.now().toString());
                    return opr.take(raw);
                }
            });
        }
    }
}
