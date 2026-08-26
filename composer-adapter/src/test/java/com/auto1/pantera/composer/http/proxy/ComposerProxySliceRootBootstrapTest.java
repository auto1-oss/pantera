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
package com.auto1.pantera.composer.http.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.misc.RandomFreePort;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.RegistryBackedInspector;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the FULL {@link ComposerProxySlice} wiring — not just
 * {@link com.auto1.pantera.composer.cooldown.ComposerRootPackagesHandler}
 * in isolation — against a local, in-process fixture standing in for
 * Packagist. No Docker, no live network: the fixture is an embedded
 * Vert.x server on loopback, so this stays a fast unit test.
 *
 * <p>This is the regression test for WS4-composer.1/.2: before the fix,
 * {@code ComposerRootPackagesHandler} was wired to {@code CachedProxySlice}
 * (the package-merge path), which mangles {@code /packages.json} into a
 * bogus package name and always 404s — a handler-level test alone cannot
 * see that regression because it constructs the handler directly, bypassing
 * {@code ComposerProxySlice}'s internal wiring entirely.</p>
 */
final class ComposerProxySliceRootBootstrapTest {

    private static final Vertx VERTX = Vertx.vertx();

    private static final String BASE_URL = "http://pantera.local/php_proxy";

    private JettyClientSlices client;

    private VertxSliceServer upstream;

    @BeforeEach
    void setUp() {
        this.client = new JettyClientSlices();
        this.client.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.upstream != null) {
            this.upstream.close();
        }
        if (this.client != null) {
            this.client.stop();
        }
    }

    @AfterAll
    static void closeVertx() {
        ComposerProxySliceRootBootstrapTest.VERTX.close();
    }

    @Test
    void standaloneProxyBootstrapsRootWithoutLocalMember() throws Exception {
        final int port = RandomFreePort.get();
        this.upstream = new VertxSliceServer(
            ComposerProxySliceRootBootstrapTest.VERTX,
            new LoggingSlice(new FakePackagistRoot()),
            port
        );
        this.upstream.start();

        final ComposerProxySlice slice = new ComposerProxySlice(
            this.client,
            URI.create("http://127.0.0.1:" + port),
            new AstoRepository(new InMemoryStorage()),
            Authenticator.ANONYMOUS,
            Cache.NOP,
            Optional.empty(),
            "php_proxy_test",
            "php-proxy",
            NoopCooldownService.INSTANCE,
            new RegistryBackedInspector("php-proxy", PublishDateRegistries.instance()),
            BASE_URL
        );

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/packages.json"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(10, TimeUnit.SECONDS);

        MatcherAssert.assertThat(
            "Standalone proxy root must bootstrap with 200, not 404",
            resp.status().code(), new IsEqual<>(200)
        );
        final String body = new String(
            resp.body().asBytesFuture().get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "metadata-url must be rewritten to the Pantera-local base",
            body, new StringContains(false, BASE_URL + "/p2/%package%.json")
        );
        MatcherAssert.assertThat(
            "No served root field may leak the upstream host",
            body, new IsNot<>(new StringContains(false, "packagist.org"))
        );
    }

    /** Serves a Packagist-shaped lazy-provider root at {@code /packages.json}. */
    private static final class FakePackagistRoot implements Slice {
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return body.asBytesFuture().thenApply(ignored -> {
                if (!"/packages.json".equals(line.uri().getPath())) {
                    return ResponseBuilder.notFound().build();
                }
                return ResponseBuilder.ok()
                    .header("Content-Type", "application/json")
                    .body(
                        """
                        {
                          "packages": [],
                          "notify": "https://packagist.org/downloads/%package%",
                          "notify-batch": "https://packagist.org/downloads/",
                          "providers-url": "https://repo.packagist.org/p/%package%$%hash%.json",
                          "list": "https://packagist.org/packages/list.json",
                          "search": "https://packagist.org/search.json?q=%query%&type=%type%",
                          "metadata-url": "https://repo.packagist.org/p2/%package%.json",
                          "available-packages-url": "https://packagist.org/packages/list.json?fields[]=name"
                        }
                        """.getBytes(StandardCharsets.UTF_8)
                    )
                    .build();
            });
        }
    }
}
