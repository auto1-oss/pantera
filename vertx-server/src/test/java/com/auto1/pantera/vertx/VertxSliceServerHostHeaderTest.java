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
package com.auto1.pantera.vertx;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.core.buffer.Buffer;
import io.vertx.reactivex.ext.web.client.HttpResponse;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Every slice that builds a client-facing URL reads the {@code Host} header.
 *
 * <p>HTTP/2 forbids {@code Host} on the wire and carries the authority in the
 * {@code :authority} pseudo-header, which Vert.x keeps out of the header map.
 * An h2 request therefore arrived hostless, the derivation fell through to a
 * literal {@code localhost}, and package metadata went out advertising
 * unreachable {@code http://localhost/...} URLs — on correctly configured,
 * allowlisted hostnames, because the allowlist was never even consulted.</p>
 *
 * @since 2.2.8
 */
final class VertxSliceServerHostHeaderTest {

    private int port;

    private Vertx vertx;

    private VertxSliceServer server;

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            this.port = socket.getLocalPort();
        }
        this.vertx = Vertx.vertx();
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody(VertxSliceServerHostHeaderTest.host(headers)).build()
            ),
            new HttpServerOptions().setPort(this.port)
        );
        this.server.start();
    }

    @AfterEach
    void tearDown() {
        if (this.server != null) {
            this.server.close();
        }
        if (this.vertx != null) {
            this.vertx.close();
        }
    }

    @Test
    @DisplayName("An HTTP/1.1 request carries its Host header through")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void httpOneCarriesHost() {
        MatcherAssert.assertThat(
            "HTTP/1.1 sends Host on the wire, so it must arrive verbatim",
            this.get(HttpVersion.HTTP_1_1), new IsEqual<>("packages.example.test")
        );
    }

    @Test
    @DisplayName("An HTTP/2 request's :authority is surfaced as Host")
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void httpTwoAuthorityBecomesHost() {
        MatcherAssert.assertThat(
            "Without this the derivation sees no host and emits http://localhost/",
            this.get(HttpVersion.HTTP_2), new IsEqual<>("packages.example.test")
        );
    }

    /**
     * Issue a request over the given protocol and return the observed Host.
     *
     * @param version Protocol version
     * @return The {@code Host} the slice saw, or {@code <ABSENT>}
     */
    private String get(final HttpVersion version) {
        final WebClient client = WebClient.create(
            this.vertx,
            new WebClientOptions()
                .setProtocolVersion(version)
                .setHttp2ClearTextUpgrade(false)
        );
        try {
            final HttpResponse<Buffer> response = client
                .get(this.port, "localhost", "/probe")
                // Addresses the loopback listener while presenting the
                // client-facing name, exactly as the load balancer does.
                .virtualHost("packages.example.test")
                .rxSend()
                .blockingGet();
            return response.bodyAsString();
        } finally {
            client.close();
        }
    }

    /**
     * The Host as the slice sees it, with any port stripped — the loopback
     * port is assigned per run and is not what this test is about.
     *
     * @param headers Request headers
     * @return Host without port, or {@code <ABSENT>} when there is none
     */
    private static String host(final Headers headers) {
        final List<String> values = headers.values("Host");
        final String result;
        if (values.isEmpty()) {
            result = "<ABSENT>";
        } else {
            final String raw = values.get(0);
            final int colon = raw.lastIndexOf(':');
            if (colon > 0) {
                result = raw.substring(0, colon);
            } else {
                result = raw;
            }
        }
        return result;
    }
}
