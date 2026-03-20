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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.client.auth.BasicAuthenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.security.policy.PolicyByUsername;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

/**
 * Test for {@link FileProxySlice} to verify it works with target requiring authentication.
 *
 * @since 0.6
 */
final class FileProxySliceAuthIT {

    /**
     * Vertx instance.
     */
    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Jetty client.
     */
    private final JettyClientSlices client = new JettyClientSlices();

    /**
     * Maven proxy.
     */
    private Slice proxy;

    /**
     * Vertx slice server instance.
     */
    private VertxSliceServer server;

    @BeforeEach
    void setUp() throws Exception {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From("foo", "bar"), new Content.From("baz".getBytes()))
            .toCompletableFuture().join();
        final String username = "alice";
        final String password = "qwerty";
        this.server = new VertxSliceServer(
            FileProxySliceAuthIT.VERTX,
            new LoggingSlice(
                new FilesSlice(
                    storage,
                    new PolicyByUsername(username),
                    new Authentication.Single(username, password),
                    FilesSlice.ANY_REPO, Optional.empty()
                )
            )
        );
        final int port = this.server.start();
        this.client.start();
        this.proxy = new LoggingSlice(
            new FileProxySlice(
                this.client,
                URI.create(String.format("http://localhost:%d", port)),
                new BasicAuthenticator(username, password),
                new InMemoryStorage()
            )
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.stop();
        this.server.stop();
    }

    @Test
    void shouldGet() {
        Assertions.assertEquals(RsStatus.OK,
            this.proxy.response(
                new RequestLine(RqMethod.GET, "/foo/bar"), Headers.EMPTY, Content.EMPTY
            ).join().status()
        );
    }
}
