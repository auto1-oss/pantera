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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Subscriber;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * HEAD on a PyPI proxy answers what GET would, without a body; other
 * methods are refused with an {@code Allow} header.
 *
 * @since 2.2.9
 */
final class PyProxySliceHeadTest {

    /**
     * Upstream simple-index page.
     */
    private static final byte[] PAGE =
        "<html><body><a href=\"six-1.16.0.tar.gz\">six</a></body></html>"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void headOfAProjectPageAnswersLikeGetWithoutABody() {
        final Response resp = PyProxySliceHeadTest.slice().response(
            new RequestLine(RqMethod.HEAD, "/six/"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "HEAD is answered like GET",
            resp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "HEAD carries no body",
            resp.body().asBytes().length, new IsEqual<>(0)
        );
    }

    @Test
    void writesAreRefusedWithTheAllowedMethods() {
        final Response resp = PyProxySliceHeadTest.slice().response(
            new RequestLine(RqMethod.PUT, "/six/"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "a write to a proxy is not allowed",
            resp.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "the refusal lists the allowed methods",
            resp.headers().values("Allow"), new IsEqual<>(List.of("GET, HEAD"))
        );
    }

    /**
     * Proxy over an upstream that serves the simple-index page.
     * @return Slice
     */
    private static Slice slice() {
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "text/html")
                .body(PyProxySliceHeadTest.PAGE)
                .build()
        );
        final ClientSlices clients = new ClientSlices() {
            @Override
            public Slice http(final String host) {
                return upstream;
            }

            @Override
            public Slice http(final String host, final int port) {
                return upstream;
            }

            @Override
            public Slice https(final String host) {
                return upstream;
            }

            @Override
            public Slice https(final String host, final int port) {
                return upstream;
            }
        };
        return new PyProxySlice(
            clients,
            URI.create("https://pypi.example/simple"),
            Authenticator.ANONYMOUS,
            new InMemoryStorage(),
            Optional.empty(),
            "pypi_proxy"
        );
    }

    @Test
    void writeRefusalDrainsTheBodyWithoutMaterialisingIt() {
        final DeclaredHugeBody body = new DeclaredHugeBody();
        final Response resp = PyProxySliceHeadTest.slice().response(
            new RequestLine(RqMethod.PUT, "/six/"), Headers.EMPTY, body
        ).join();
        MatcherAssert.assertThat(
            "a write to a proxy is refused",
            resp.status(), new IsEqual<>(RsStatus.METHOD_NOT_ALLOWED)
        );
        MatcherAssert.assertThat(
            "the refusal must not pre-allocate the client's declared Content-Length",
            body.materialised.get(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the refusal still consumes the request body",
            body.subscribed.get(), new IsEqual<>(true)
        );
    }

    /**
     * Request body that declares ~2 GB but carries a few bytes, and records
     * whether it was materialised with {@code asBytesFuture()} (which
     * pre-allocates from the declared length) or merely drained.
     */
    private static final class DeclaredHugeBody implements Content {

        private final AtomicBoolean materialised = new AtomicBoolean();

        private final AtomicBoolean subscribed = new AtomicBoolean();

        @Override
        public Optional<Long> size() {
            return Optional.of(2_000_000_000L);
        }

        @Override
        public CompletableFuture<byte[]> asBytesFuture() {
            this.materialised.set(true);
            return Content.super.asBytesFuture();
        }

        @Override
        public void subscribe(final Subscriber<? super ByteBuffer> subscriber) {
            this.subscribed.set(true);
            Flowable.just(
                ByteBuffer.wrap("ten  bytes".getBytes(StandardCharsets.UTF_8))
            ).subscribe(subscriber);
        }
    }
}
