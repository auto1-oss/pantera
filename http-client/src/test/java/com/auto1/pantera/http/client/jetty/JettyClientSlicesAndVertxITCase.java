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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.reactivex.Flowable;
import io.vertx.reactivex.core.Vertx;
import org.cactoos.text.TextOf;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link JettyClientSlices} and vertx.
 */
final class JettyClientSlicesAndVertxITCase {

    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Clients.
     */
    private final JettyClientSlices clients = new JettyClientSlices();

    /**
     * Vertx slice server instance.
     */
    private VertxSliceServer server;

    @BeforeEach
    void setUp() throws Exception {
        this.clients.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.clients.stop();
        if (this.server != null) {
            this.server.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void getsSomeContent(final boolean anonymous) throws IOException {
        final int port = this.startServer(anonymous);
        final HttpURLConnection con = (HttpURLConnection)
            URI.create(String.format("http://localhost:%s", port)).toURL().openConnection();
        con.setRequestMethod("GET");
        MatcherAssert.assertThat(
            "Response status is 200",
            con.getResponseCode(),
            new IsEqual<>(RsStatus.OK.code())
        );
        MatcherAssert.assertThat(
            "Response body is some html",
            new TextOf(con.getInputStream()).toString(),
            Matchers.startsWith("<!DOCTYPE html>")
        );
        con.disconnect();
    }

    private int startServer(final boolean anonymous) {
        this.server = new VertxSliceServer(
            JettyClientSlicesAndVertxITCase.VERTX,
            new LoggingSlice(new ProxySlice(this.clients, anonymous))
        );
        return this.server.start();
    }

    /**
     * Test proxy slice.
     * @since 0.3
     */
    static final class ProxySlice implements Slice {

        /**
         * Client.
         */
        private final ClientSlices client;

        /**
         * Anonymous flag.
         */
        private final boolean anonymous;

        /**
         * Ctor.
         * @param client Http client
         * @param anonymous Anonymous flag
         */
        ProxySlice(final ClientSlices client, final boolean anonymous) {
            this.client = client;
            this.anonymous = anonymous;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content pub
        ) {
            final CompletableFuture<Response> promise = new CompletableFuture<>();
            final Slice origin = this.client.https("blog.pantera.com");
            final Slice slice;
            if (this.anonymous) {
                slice = origin;
            } else {
                slice = new AuthClientSlice(origin, Authenticator.ANONYMOUS);
            }
            slice.response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY,
                Content.EMPTY
            ).thenAccept(resp -> {
                final CompletableFuture<Void> terminated = new CompletableFuture<>();
                final Flowable<ByteBuffer> termbody = Flowable.fromPublisher(resp.body())
                    .doOnError(terminated::completeExceptionally)
                    .doOnTerminate(() -> terminated.complete(null));
                promise.complete(ResponseBuilder.from(resp.status())
                    .headers(resp.headers())
                    .body(termbody)
                    .build());
            });
            return promise;
        }
    }
}
