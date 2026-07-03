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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.client.HttpServer;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import io.vertx.core.http.HttpServerOptions;
import org.eclipse.jetty.client.HttpClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link JettyClientSlice} with HTTP server.
 */
class JettyClientSliceTest {

    /**
     * Test server.
     */
    private final HttpServer server = new HttpServer();

    /**
     * HTTP client used in tests.
     */
    private HttpClient client;

    /**
     * HTTP client sliced being tested.
     */
    private JettyClientSlice slice;

    @BeforeEach
    void setUp() throws Exception {
        final int port = this.server.start(this.newHttpServerOptions());
        this.client = this.newHttpClient();
        this.client.start();
        this.slice = new JettyClientSlice(
            this.client,
            this.client.getSslContextFactory().isTrustAll(),
            "localhost",
            port,
            0L
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        this.server.stop();
        this.client.stop();
    }

    HttpClient newHttpClient() {
        return new HttpClient();
    }

    HttpServerOptions newHttpServerOptions() {
        return new HttpServerOptions().setPort(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PUT /",
        "GET /index.html",
        "POST /path?param1=value&param2=something",
        "HEAD /my%20path?param=some%20value"
    })
    void shouldSendRequestLine(final String line) {
        final AtomicReference<RequestLine> actual = new AtomicReference<>();
        this.server.update(
            (rqline, rqheaders, rqbody) -> {
                actual.set(rqline);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        );
        this.slice.response(
            RequestLine.from(String.format("%s HTTP/1.1", line)),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            actual.get().toString(),
            new StringStartsWith(String.format("%s HTTP", line))
        );
    }

    @Test
    void shouldSendHeaders() {
        final AtomicReference<Headers> actual = new AtomicReference<>();
        this.server.update(
            (line, headers, content) -> {
                System.out.println("MY_DEBUG " + headers);
                actual.set(headers);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        );
        this.slice.response(
            new RequestLine(RqMethod.GET, "/something"),
            Headers.from(
                new Header("My-Header", "MyValue"),
                new Header("Another-Header", "AnotherValue")
            ),
            Content.EMPTY
        ).join();
        Assertions.assertEquals("MyValue", actual.get().values("My-Header").getFirst());
        Assertions.assertEquals("AnotherValue", actual.get().values("Another-Header").getFirst());
    }

    @Test
    void shouldSendBody() {
        final byte[] content = "some content".getBytes();
        final AtomicReference<byte[]> actual = new AtomicReference<>();
        this.server.update(
            (rqline, rqheaders, rqbody) ->
                new Content.From(rqbody).asBytesFuture().thenApply(
                    bytes -> {
                        actual.set(bytes);
                        return ResponseBuilder.ok().build();
                    }
            )
        );
        this.slice.response(
            new RequestLine(RqMethod.PUT, "/package"),
            Headers.EMPTY,
            new Content.From(content)
        ).join();
        MatcherAssert.assertThat(
            actual.get(),
            new IsEqual<>(content)
        );
    }

    @Test
    void shouldReceiveStatus() {
        this.server.update((rqline, rqheaders, rqbody) -> CompletableFuture.completedFuture(
            ResponseBuilder.notFound().build())
        );
        Assertions.assertEquals(
            RsStatus.NOT_FOUND,
            this.slice.response(
                new RequestLine(RqMethod.GET, "/a/b/c"),
                    Headers.EMPTY, Content.EMPTY)
                .join().status()
        );
    }

    @Test
    void shouldReceiveHeaders() {
        this.server.update(
            (rqline, rqheaders, rqbody) ->
                CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header(ContentType.text())
                        .header(new Header("WWW-Authenticate", "Basic"))
                        .build()
                )
        );
        MatcherAssert.assertThat(
            this.slice.response(new RequestLine(RqMethod.HEAD, "/content"),
                Headers.EMPTY, Content.EMPTY).join().headers(),
            Matchers.hasItems(
                ContentType.text(),
                new Header("WWW-Authenticate", "Basic")
            )
        );
    }

    @Test
    void shouldReceiveBody() {
        this.server.update(
            (rqline, rqheaders, rqbody) ->
                CompletableFuture.completedFuture(
                    ResponseBuilder.ok().textBody("data").build()
                )
        );
        Assertions.assertEquals(
            "data",
            this.slice.response(
                new RequestLine(RqMethod.PATCH, "/file.txt"),
                Headers.EMPTY, Content.EMPTY
            ).join().body().asString()
        );
    }

    /**
     * Regression for fix(http-client): preserve raw inbound path. The earlier
     * Apache {@code URIBuilder} round-trip percent-encoded characters that
     * RFC 3986 §3.3 lists as valid {@code pchar} — {@code @} (sub-delim),
     * {@code :}, {@code +}, {@code ,}, {@code ;}, {@code =}, {@code !},
     * {@code *}, {@code (}, {@code )}, {@code '} — and that broke real
     * upstreams: {@code proxy.golang.org} RST_STREAMs on {@code %40v};
     * strict private npm registries 404 on {@code %40scope};
     * PyPI-strict mirrors fail on {@code %2B} in local-version wheel
     * names. The test pins the wire shape of these paths to whatever
     * the inbound client sent.
     *
     * <p>Known carve-out: {@code $} (also an RFC sub-delim) is still
     * percent-encoded to {@code %24} by Jetty 12's transport layer
     * during URI canonicalisation — this is internal to the Jetty
     * client and is not reachable from Pantera without forking
     * {@code org.eclipse.jetty.http.HttpURI}. Composer's Packagist v2
     * sha-pinned URLs ({@code /p2/<vendor>/<pkg>$<sha>.json}) are the
     * only known Pantera path affected, and Packagist accepts the
     * {@code %24} form. Re-add {@code "/p2/sym/console$abc.json"} to
     * this list when a Jetty upgrade or workaround eliminates the
     * remaining encoding.
     *
     * @param path Path the test client emits; it must arrive at the test
     *             server verbatim.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/go.uber.org/multierr/@v/v1.10.0.zip",
        "/@types/node",
        "/v2/library/nginx/manifests/sha256:abc123",
        "/wheel/torch-2.0+cu118-py3.whl",
        "/path,with;sub=delims!and*more('quoted')"
    })
    void shouldPreservePcharInUpstreamPath(final String path) {
        final AtomicReference<String> received = new AtomicReference<>();
        this.server.update(
            (rqline, rqheaders, rqbody) -> {
                received.set(rqline.uri().getRawPath());
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        );
        this.slice.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(received.get(), new IsEqual<>(path));
    }
}
