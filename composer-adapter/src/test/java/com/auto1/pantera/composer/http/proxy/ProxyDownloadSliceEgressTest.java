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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exploit-regression test for the Composer {@code dist.url} SSRF: package
 * metadata is publisher-influenced and its {@code dist.url} drove a
 * server-side request to whatever origin it named. Before 2.2.9 a
 * {@code dist.url} pointing at the cloud metadata service was dialed and its
 * body returned/cached. A denied destination must never be dialed.
 *
 * @since 2.2.9
 */
final class ProxyDownloadSliceEgressTest {

    private static final URI UPSTREAM = URI.create("https://packagist.example");

    @Test
    @Timeout(20)
    void distUrlOnCloudMetadataIsNeverDialed() throws Exception {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("acme/widget.json"),
            new Content.From((
                "{\"packages\":{\"acme/widget\":{\"1.0.0\":{\"version\":\"1.0.0\","
                    + "\"dist\":{\"url\":\"http://169.254.169.254/latest/meta-data/\"}}}}}"
            ).getBytes(StandardCharsets.UTF_8))
        ).join();
        final AtomicBoolean dialed = new AtomicBoolean();
        final ClientSlices clients = new ClientSlices() {
            @Override
            public Slice http(final String host) {
                return this.mark();
            }

            @Override
            public Slice http(final String host, final int port) {
                return this.mark();
            }

            @Override
            public Slice https(final String host) {
                return this.mark();
            }

            @Override
            public Slice https(final String host, final int port) {
                return this.mark();
            }

            private Slice mark() {
                dialed.set(true);
                return (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body("iam-creds".getBytes(StandardCharsets.UTF_8)).build()
                );
            }
        };
        final Slice upstream = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.notFound().build()
        );
        final ProxyDownloadSlice slice = new ProxyDownloadSlice(
            upstream, clients, UPSTREAM, Optional.empty(), "composer-proxy",
            "composer-proxy", storage, NoopCooldownService.INSTANCE, new NoDates()
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/dist/acme/widget/1.0.0.zip"),
            Headers.EMPTY, Content.EMPTY
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a dist.url on the cloud metadata address must never be dialed",
            dialed.get(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the denied dist must be answered as an upstream failure, not served",
            response.status().code() >= 500, new IsEqual<>(true)
        );
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
