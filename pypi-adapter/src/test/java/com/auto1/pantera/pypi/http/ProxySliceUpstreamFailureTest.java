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
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.LocalRefusal;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.log.AnswerReason;
import com.auto1.pantera.http.log.RequestReasons;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * A PyPI proxy tells an upstream that has no such file from an upstream that
 * could not be asked: only the first is a 404, and only the first is
 * remembered in the negative cache.
 *
 * @since 2.2.14
 */
final class ProxySliceUpstreamFailureTest {

    /**
     * Distribution file, fetched on the artifact path.
     */
    private static final String WHEEL = "/example_project-1.2.3-py3-none-any.whl";

    /**
     * Core-metadata file, fetched on the non-artifact path.
     */
    private static final String METADATA = "/example_project-1.2.3-py3-none-any.whl.metadata";

    @Test
    void upstreamNotFoundIsNotFoundWithUpstreamReason() {
        final Answer answer = ProxySliceUpstreamFailureTest.get(
            ProxySliceUpstreamFailureTest.WHEEL,
            () -> CompletableFuture.completedFuture(ResponseBuilder.notFound().build())
        );
        MatcherAssert.assertThat(
            "upstream 404 stays a 404", answer.status, new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            answer.reason, new IsEqual<>(Optional.of("upstream_status_404"))
        );
    }

    @Test
    void upstreamServerErrorOnFileIsBadGateway() {
        final Answer answer = ProxySliceUpstreamFailureTest.get(
            ProxySliceUpstreamFailureTest.WHEEL,
            () -> CompletableFuture.completedFuture(ResponseBuilder.unavailable().build())
        );
        MatcherAssert.assertThat(
            "upstream 503 is not a missing file",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the upstream status is the reason",
            answer.reason, new IsEqual<>(Optional.of("upstream_status_503"))
        );
    }

    @Test
    void failedUpstreamCallOnFileIsBadGateway() {
        final Answer answer = ProxySliceUpstreamFailureTest.get(
            ProxySliceUpstreamFailureTest.WHEEL,
            () -> CompletableFuture.failedFuture(new ConnectException("Connection refused"))
        );
        MatcherAssert.assertThat(
            "a refused connection is not a missing file",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the transport failure is the reason",
            answer.reason, new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    void openCircuitOnFileKeepsItsMarker() {
        final Answer answer = ProxySliceUpstreamFailureTest.get(
            ProxySliceUpstreamFailureTest.WHEEL,
            () -> CompletableFuture.completedFuture(
                ResponseBuilder.badGateway()
                    .header(UpstreamCircuitOpenException.HEADER, "true")
                    .header("Retry-After", "30").build()
            )
        );
        MatcherAssert.assertThat(
            "a group must be able to skip this member: the refusal is marked",
            new LocalRefusal(
                new Response(answer.status, answer.headers, Content.EMPTY)
            ).present(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the breaker's Retry-After is relayed",
            answer.headers.values("Retry-After"), new IsEqual<>(List.of("30"))
        );
    }

    @Test
    void upstreamThrottlingOnMetadataIsUnavailableWithRetryAfter() {
        final Answer answer = ProxySliceUpstreamFailureTest.get(
            ProxySliceUpstreamFailureTest.METADATA,
            () -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "7").build()
            )
        );
        MatcherAssert.assertThat(
            "upstream 429 asks the client to repeat",
            answer.status, new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE)
        );
        MatcherAssert.assertThat(
            "the upstream's Retry-After is relayed",
            answer.headers.values("Retry-After"), new IsEqual<>(List.of("7"))
        );
    }

    @Test
    void failedUpstreamCallOnMetadataIsBadGateway() {
        final Answer answer = ProxySliceUpstreamFailureTest.get(
            ProxySliceUpstreamFailureTest.METADATA,
            () -> CompletableFuture.failedFuture(new ConnectException("Connection refused"))
        );
        MatcherAssert.assertThat(
            "a refused connection is not a missing file",
            answer.status, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the transport failure is the reason",
            answer.reason, new IsEqual<>(Optional.of("connection_refused"))
        );
    }

    @Test
    void upstreamFailureIsNotRememberedAsNotFound() {
        final AtomicInteger calls = new AtomicInteger();
        final Slice cached = ProxySliceUpstreamFailureTest.cached(
            ProxySliceUpstreamFailureTest.proxy(
                () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.unavailable().build()
                    );
                }
            ),
            "pypi-proxy-outage"
        );
        final RsStatus first = ProxySliceUpstreamFailureTest.status(
            cached, ProxySliceUpstreamFailureTest.METADATA
        );
        final RsStatus second = ProxySliceUpstreamFailureTest.status(
            cached, ProxySliceUpstreamFailureTest.METADATA
        );
        MatcherAssert.assertThat(
            "the outage is answered 502", first, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the repeat is answered 502 as well", second, new IsEqual<>(RsStatus.BAD_GATEWAY)
        );
        MatcherAssert.assertThat(
            "the repeat asks the upstream again: nothing was negative-cached",
            calls.get(), new IsEqual<>(2)
        );
    }

    @Test
    void upstreamRefusalIsNotRememberedAsNotFound() {
        final AtomicInteger calls = new AtomicInteger();
        final Slice cached = ProxySliceUpstreamFailureTest.cached(
            ProxySliceUpstreamFailureTest.proxy(
                () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.unauthorized().build()
                    );
                }
            ),
            "pypi-proxy-refused"
        );
        final RsStatus first = ProxySliceUpstreamFailureTest.status(
            cached, ProxySliceUpstreamFailureTest.METADATA
        );
        ProxySliceUpstreamFailureTest.status(cached, ProxySliceUpstreamFailureTest.METADATA);
        MatcherAssert.assertThat(
            "an upstream 401 concerns Pantera's credentials: the client gets 404",
            first, new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "it is not negative-cached: fixing the credentials takes effect at once",
            calls.get(), new IsEqual<>(2)
        );
    }

    private static RsStatus status(final Slice slice, final String path) {
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        return resp.status();
    }

    private static Answer get(
        final String path, final Supplier<CompletableFuture<Response>> upstream
    ) {
        final String slot = RequestReasons.instance().open();
        final Response resp = ProxySliceUpstreamFailureTest.proxy(upstream).response(
            new RequestLine(RqMethod.GET, path),
            Headers.from(RequestReasons.SLOT_HEADER, slot),
            Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        final Optional<String> noted = RequestReasons.instance().close(slot);
        return new Answer(
            resp.status(), resp.headers(), noted.or(() -> new AnswerReason(resp).token())
        );
    }

    private static Slice cached(final Slice origin, final String name) {
        return new CachedPyProxySlice(
            origin, Optional.empty(), Duration.ofHours(1), true,
            name, "https://upstream.example/pypi", "pypi-proxy"
        );
    }

    private static ProxySlice proxy(final Supplier<CompletableFuture<Response>> upstream) {
        final InMemoryStorage storage = new InMemoryStorage();
        return new ProxySlice(
            new NoClients(),
            Authenticator.ANONYMOUS,
            (line, headers, body) -> upstream.get(),
            storage,
            new FromStorageCache(storage),
            Optional.empty(),
            "pypi_proxy",
            "pypi-proxy",
            NoopCooldownService.INSTANCE,
            new RegistryBackedInspector("pypi", PublishDateRegistries.instance()),
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            )
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
     * Mirror clients; the requests under test never reach a mirror.
     */
    private static final class NoClients implements ClientSlices {
        @Override
        public Slice http(final String host) {
            return NoClients.none();
        }

        @Override
        public Slice http(final String host, final int port) {
            return NoClients.none();
        }

        @Override
        public Slice https(final String host) {
            return NoClients.none();
        }

        @Override
        public Slice https(final String host, final int port) {
            return NoClients.none();
        }

        private static Slice none() {
            return (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        }
    }
}
