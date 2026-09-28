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
import io.vertx.core.http.HttpServerOptions;
import io.vertx.reactivex.core.Vertx;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link VertxSliceServer#start()} blocks until the socket is bound, and the
 * bind completes on an event loop. Called ON an event loop it used to wait
 * for itself forever (a repository created at runtime with a dedicated
 * {@code port} froze that event loop). It must now fail fast there and keep
 * working from a worker thread.
 *
 * @since 2.2.9
 */
final class VertxSliceServerEventLoopStartTest {

    private Vertx vertx;

    private VertxSliceServer server;

    @BeforeEach
    void setUp() {
        this.vertx = Vertx.vertx();
        this.server = new VertxSliceServer(
            this.vertx,
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            ),
            new HttpServerOptions().setPort(0).setHost("localhost"),
            Duration.ZERO
        );
    }

    @AfterEach
    void tearDown() {
        this.server.close();
        this.vertx.close();
    }

    @Test
    @Timeout(60)
    void startOnAnEventLoopFailsFastInsteadOfDeadlocking() throws Exception {
        final CompletableFuture<Throwable> res = new CompletableFuture<>();
        this.vertx.getDelegate().runOnContext(
            nothing -> {
                try {
                    this.server.start();
                    res.complete(null);
                } catch (final IllegalStateException ex) {
                    res.complete(ex);
                }
            }
        );
        MatcherAssert.assertThat(
            res.get(30, TimeUnit.SECONDS),
            new IsInstanceOf(IllegalStateException.class)
        );
    }

    @Test
    @Timeout(60)
    void startOnAWorkerThreadBinds() throws Exception {
        final int port = this.vertx.getDelegate()
            .executeBlocking(() -> this.server.start(), false)
            .toCompletionStage().toCompletableFuture()
            .get(30, TimeUnit.SECONDS);
        MatcherAssert.assertThat(port > 0, new IsEqual<>(true));
    }
}
