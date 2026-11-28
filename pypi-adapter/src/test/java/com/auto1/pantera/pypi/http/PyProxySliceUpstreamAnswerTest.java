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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.ReasonSlice;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What a PyPI proxy, built as the server builds it ({@link CachedPyProxySlice}
 * over {@link PyProxySlice}, whose cache streams the upstream's answer
 * through), answers for what its upstream did. A project page the upstream
 * serves without files is "not found", never a failure of the upstream: a
 * group would count a failure against the member and pip would report an
 * error instead of "no matching distribution".
 *
 * @since 2.2.15
 */
final class PyProxySliceUpstreamAnswerTest {

    /**
     * Source of repository names: the negative cache is shared, so every
     * proxy of this class has a name of its own.
     */
    private static final AtomicInteger REPOS = new AtomicInteger();

    /**
     * Accept header of pip.
     */
    private static final String HTML = "text/html";

    /**
     * Accept header of uv (PEP 691).
     */
    private static final String JSON = "application/vnd.pypi.simple.v1+json";

    @ParameterizedTest
    @CsvSource({
        "json,/empty-pkg/," + PyProxySliceUpstreamAnswerTest.HTML,
        "json,/empty-pkg/," + PyProxySliceUpstreamAnswerTest.JSON,
        "html,/empty-pkg/," + PyProxySliceUpstreamAnswerTest.HTML,
        "html,/empty-pkg/," + PyProxySliceUpstreamAnswerTest.JSON
    })
    @Timeout(120)
    void projectPageWithoutFilesIsNotFound(
        final String shape, final String path, final String accept
    ) throws Exception {
        final FakeUpstream upstream = new FakeUpstream();
        final byte[] page;
        if ("json".equals(shape)) {
            page = "{\"meta\":{\"api-version\":\"1.1\"},\"name\":\"empty-pkg\",\"files\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        } else {
            page = "<!DOCTYPE html><html><body><h1>Links for empty-pkg</h1></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        }
        upstream.answer(
            "/simple/empty-pkg/",
            () -> CompletableFuture.completedFuture(ResponseBuilder.ok().body(page).build())
        );
        final Answer answer = PyProxySliceUpstreamAnswerTest.get(upstream, path, accept);
        MatcherAssert.assertThat(
            "a page that lists no file is answered as a missing project",
            answer.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the reason says the upstream listed no file, not that it said 404",
            answer.reason(), new IsEqual<>(Optional.of("upstream_no_files"))
        );
        MatcherAssert.assertThat(
            "and it is remembered like an upstream 404",
            answer.headers().values(NegativeCache.SKIP_HEADER), new IsEqual<>(List.of())
        );
    }

    @ParameterizedTest
    @CsvSource({
        "/example-pkg/,/simple/example-pkg/,404,,NOT_FOUND,upstream_status_404,false",
        "/example-pkg/,/simple/example-pkg/,410,,NOT_FOUND,upstream_status_410,false",
        "/example-pkg/,/simple/example-pkg/,403,,NOT_FOUND,upstream_status_403,true",
        "/example-pkg/,/simple/example-pkg/,429,7,SERVICE_UNAVAILABLE,upstream_status_429,false",
        "/example-pkg/,/simple/example-pkg/,503,7,SERVICE_UNAVAILABLE,upstream_status_503,false",
        "/example-pkg/,/simple/example-pkg/,503,,BAD_GATEWAY,upstream_status_503,false",
        "/example-pkg/,/simple/example-pkg/,500,,BAD_GATEWAY,upstream_status_500,false",
        "/example-pkg/,/simple/example-pkg/,0,,BAD_GATEWAY,connection_refused,false",
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "404,,NOT_FOUND,upstream_status_404,false",
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "403,,NOT_FOUND,upstream_status_403,true",
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "429,7,SERVICE_UNAVAILABLE,upstream_status_429,false",
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "500,,BAD_GATEWAY,upstream_status_500,false",
        "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "/packages/aa/bb/example_pkg-1.0.0-py3-none-any.whl,"
            + "0,,BAD_GATEWAY,connection_refused,false",
        "/packages/aa/bb/example_pkg-1.0.0.tar.gz,/packages/aa/bb/example_pkg-1.0.0.tar.gz,"
            + "404,,NOT_FOUND,upstream_status_404,false",
        "/packages/aa/bb/example_pkg-1.0.0.tar.gz,/packages/aa/bb/example_pkg-1.0.0.tar.gz,"
            + "503,7,SERVICE_UNAVAILABLE,upstream_status_503,false",
        "/packages/aa/bb/example_pkg-1.0.0.tar.gz,/packages/aa/bb/example_pkg-1.0.0.tar.gz,"
            + "0,,BAD_GATEWAY,connection_refused,false",
        "/packages/aa/bb/example_pkg-1.0.0.egg,/packages/aa/bb/example_pkg-1.0.0.egg,"
            + "404,,NOT_FOUND,upstream_status_404,false",
        "/packages/aa/bb/example_pkg-1.0.0.egg,/packages/aa/bb/example_pkg-1.0.0.egg,"
            + "410,,NOT_FOUND,upstream_status_410,false",
        "/packages/aa/bb/example_pkg-1.0.0.egg,/packages/aa/bb/example_pkg-1.0.0.egg,"
            + "403,,NOT_FOUND,upstream_status_403,true",
        "/packages/aa/bb/example_pkg-1.0.0.egg,/packages/aa/bb/example_pkg-1.0.0.egg,"
            + "500,,BAD_GATEWAY,upstream_status_500,false"
    })
    @Timeout(120)
    void upstreamAnswerIsMappedForTheClient(
        final String path, final String asked, final int code, final String retry,
        final RsStatus expected, final String reason, final boolean skip
    ) throws Exception {
        final FakeUpstream upstream = new FakeUpstream();
        upstream.answer(asked, PyProxySliceUpstreamAnswerTest.upstream(code, retry));
        final Answer answer = PyProxySliceUpstreamAnswerTest.get(
            upstream, path, PyProxySliceUpstreamAnswerTest.HTML
        );
        MatcherAssert.assertThat(
            "the client gets the status the upstream outcome maps to",
            answer.status(), new IsEqual<>(expected)
        );
        MatcherAssert.assertThat(
            "and the reason names the upstream outcome",
            answer.reason(), new IsEqual<>(Optional.of(reason))
        );
        MatcherAssert.assertThat(
            "a 404 for a refusal is never negative-cached",
            !answer.headers().values(NegativeCache.SKIP_HEADER).isEmpty(),
            new IsEqual<>(skip)
        );
        MatcherAssert.assertThat(
            "the upstream's Retry-After is relayed",
            answer.headers().values("Retry-After"),
            new IsEqual<>(
                expected == RsStatus.SERVICE_UNAVAILABLE ? List.of(retry) : List.of()
            )
        );
    }

    /**
     * Upstream answer.
     * @param code Status, 0 for a refused connection
     * @param retry Retry-After value, null for none
     * @return Supplier of the answer
     */
    private static Supplier<CompletableFuture<Response>> upstream(
        final int code, final String retry
    ) {
        return () -> {
            final CompletableFuture<Response> res;
            if (code == 0) {
                res = CompletableFuture.failedFuture(new ConnectException("Connection refused"));
            } else {
                final ResponseBuilder builder = ResponseBuilder.from(RsStatus.byCode(code));
                if (retry != null) {
                    builder.header("Retry-After", retry);
                }
                res = CompletableFuture.completedFuture(
                    builder.body("upstream page".getBytes(StandardCharsets.UTF_8)).build()
                );
            }
            return res;
        };
    }

    /**
     * Send a GET to a new proxy over the upstream and read the whole answer.
     * The proxy is wrapped as the server wraps every repository, in the
     * slice that puts the reason on its answer for the access record.
     * @param upstream Upstream
     * @param path Request path
     * @param accept Accept header value
     * @return What the proxy answered
     * @throws Exception On failure or timeout
     */
    private static Answer get(
        final ClientSlices upstream, final String path, final String accept
    ) throws Exception {
        final Response response = PyProxySliceUpstreamAnswerTest.proxy(upstream).response(
            new RequestLine(RqMethod.GET, path),
            Headers.from(new Header("Accept", accept)),
            Content.EMPTY
        ).get(60, TimeUnit.SECONDS);
        response.body().asBytesFuture().get(60, TimeUnit.SECONDS);
        return new Answer(
            response.status(), response.headers(), new AnswerReason(response).token()
        );
    }

    /**
     * A PyPI proxy as the server builds it, in the reason slice.
     * @param upstream Upstream
     * @return Proxy
     */
    private static Slice proxy(final ClientSlices upstream) {
        final String name = "pypi_answer_" + PyProxySliceUpstreamAnswerTest.REPOS.incrementAndGet();
        final InMemoryStorage storage = new InMemoryStorage();
        final Queue<ProxyArtifactEvent> events = new ConcurrentLinkedQueue<>();
        return new ReasonSlice(
            new CachedPyProxySlice(
                new PyProxySlice(
                    upstream, URI.create("https://pypi.example/simple"),
                    Authenticator.ANONYMOUS, storage, Optional.of(events),
                    name, "pypi-proxy", NoopCooldownService.INSTANCE
                ),
                Optional.of(storage), Duration.ofHours(24), true,
                name, "https://pypi.example/simple", "pypi-proxy"
            ),
            name, "pypi-proxy"
        );
    }

    /**
     * What the proxy answered.
     * @param status Status
     * @param headers Headers
     * @param reason Reason the request ended with
     */
    private record Answer(RsStatus status, Headers headers, Optional<String> reason) {
    }

    /**
     * Upstream that answers each path as it was told, whatever the host, and
     * 404 for any other path.
     */
    private static final class FakeUpstream implements ClientSlices {

        /**
         * Answers by path.
         */
        private final Map<String, Supplier<CompletableFuture<Response>>> answers =
            new ConcurrentHashMap<>();

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
         * Answer a path.
         * @param path Path at the upstream
         * @param answer Answer
         */
        void answer(final String path, final Supplier<CompletableFuture<Response>> answer) {
            this.answers.put(path, answer);
        }

        private Slice slice() {
            return (line, headers, body) -> this.answers.getOrDefault(
                line.uri().getPath(),
                () -> CompletableFuture.completedFuture(ResponseBuilder.notFound().build())
            ).get();
        }
    }
}
