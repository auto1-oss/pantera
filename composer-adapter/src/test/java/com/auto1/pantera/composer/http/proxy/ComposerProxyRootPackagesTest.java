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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Composer pointed directly at a php-proxy fetches {@code /packages.json}
 * first. The proxy must answer it with a root whose {@code metadata-url}
 * sends the per-package lookups back to the proxy itself.
 *
 * @since 2.2.9
 */
final class ComposerProxyRootPackagesTest {

    @Test
    void servesRootPackagesJsonPointingAtTheProxy() {
        final AtomicInteger upstream = new AtomicInteger();
        final Response resp = ComposerProxyRootPackagesTest.proxy(upstream).response(
            new RequestLine(RqMethod.GET, "/packages.json"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "packages.json is served", resp.status().code(), new IsEqual<>(200)
        );
        final JsonObject root = Json.createReader(
            new java.io.StringReader(
                new String(resp.body().asBytes(), StandardCharsets.UTF_8)
            )
        ).readObject();
        MatcherAssert.assertThat(
            "metadata-url points at the proxy's own p2 endpoint",
            root.getString("metadata-url"),
            new IsEqual<>("/test_prefix/api/php_proxy/p2/%package%.json")
        );
        MatcherAssert.assertThat(
            "no upstream call is needed for the root",
            upstream.get(), new IsEqual<>(0)
        );
    }

    @Test
    void packagesPathMetadataIsLookedUpInsteadOfFailing() {
        final AtomicInteger upstream = new AtomicInteger();
        final Response resp = ComposerProxyRootPackagesTest.proxy(upstream).response(
            new RequestLine(RqMethod.GET, "/packages/acme/foo.json"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "an upstream miss is a 404, not a key-construction failure",
            resp.status().code(), new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "the upstream was asked", upstream.get(), new IsEqual<>(1)
        );
    }

    private static ComposerProxySlice proxy(final AtomicInteger upstream) {
        final Slice remote = (line, headers, body) -> {
            upstream.incrementAndGet();
            return CompletableFuture.completedFuture(ResponseBuilder.notFound().build());
        };
        return new ComposerProxySlice(
            new StaticClients(remote),
            URI.create("https://repo.packagist.example"),
            new AstoRepository(new InMemoryStorage()),
            Authenticator.ANONYMOUS,
            new ComposerStorageCache(new AstoRepository(new InMemoryStorage())),
            Optional.empty(),
            "php_proxy",
            "php-proxy",
            NoopCooldownService.INSTANCE,
            new NoDates(),
            "http://pantera.example:8080/test_prefix/api/php_proxy"
        );
    }

    /**
     * Client slices that always return the same slice.
     */
    private static final class StaticClients implements ClientSlices {
        private final Slice slice;

        StaticClients(final Slice slice) {
            this.slice = slice;
        }

        @Override
        public Slice http(final String host) {
            return this.slice;
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice;
        }

        @Override
        public Slice https(final String host) {
            return this.slice;
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice;
        }
    }

    /**
     * Inspector with no release dates.
     */
    private static final class NoDates implements CooldownInspector {
        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
