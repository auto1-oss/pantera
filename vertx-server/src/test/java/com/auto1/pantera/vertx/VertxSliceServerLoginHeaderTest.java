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

import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.auth.AuthzSlice;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.client.WebClient;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The internal login header names the authenticated user and is stamped by
 * the authorization slices. A client sending it must not reach any slice
 * with it, or the claim would be read as the request's user wherever the
 * login is resolved before (or without) an authorization slice.
 *
 * @since 2.2.9
 */
final class VertxSliceServerLoginHeaderTest {

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
                ResponseBuilder.ok()
                    .textBody(String.valueOf(headers.values(AuthzSlice.LOGIN_HDR).size()))
                    .build()
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
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void dropsClientLoginHeader() {
        final WebClient client = WebClient.create(this.vertx);
        try {
            MatcherAssert.assertThat(
                client.get(this.port, "localhost", "/repo/file")
                    .putHeader("pantera_login", "admin")
                    .putHeader("Pantera_Login", "root")
                    .rxSend()
                    .blockingGet()
                    .bodyAsString(),
                new IsEqual<>("0")
            );
        } finally {
            client.close();
        }
    }
}
