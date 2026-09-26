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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * HEAD through the whole {@link MavenProxySlice} route on a cached
 * artifact: answered from storage with the stored size and a Maven
 * Content-Type, without calling upstream.
 *
 * @since 2.2.9
 */
final class MavenProxySliceHeadTest {

    @Test
    void headOnACachedArtifactReportsItsStoredSize() {
        final byte[] bytes = "cached-jar-bytes".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("com/example/lib/1.0/lib-1.0.jar"), new Content.From(bytes)
        ).join();
        final AtomicInteger calls = new AtomicInteger();
        final Response resp = new MavenProxySlice(
            new FailingClients(calls),
            URI.create("https://repo.example.com/maven2"),
            Authenticator.ANONYMOUS,
            Cache.NOP,
            Optional.empty(),
            "maven_proxy",
            "maven-proxy",
            NoopCooldownService.INSTANCE,
            Optional.of(storage)
        ).response(
            new RequestLine(RqMethod.HEAD, "/com/example/lib/1.0/lib-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat("200 from cache", resp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat(
            "Content-Length is the stored size",
            resp.headers().values("Content-Length"),
            new IsEqual<>(List.of(String.valueOf(bytes.length)))
        );
        MatcherAssert.assertThat(
            "Content-Type is the Maven type of a jar",
            resp.headers().values("Content-Type"),
            new IsEqual<>(List.of("application/java-archive"))
        );
        MatcherAssert.assertThat("upstream not called", calls.get(), new IsEqual<>(0));
    }

    /**
     * Client slices whose every slice fails and counts the call.
     * @since 2.2.9
     */
    private static final class FailingClients implements ClientSlices {

        /**
         * Upstream call counter.
         */
        private final AtomicInteger calls;

        /**
         * Ctor.
         * @param calls Upstream call counter
         */
        FailingClients(final AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Slice http(final String host) {
            return this.failing();
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.failing();
        }

        @Override
        public Slice https(final String host) {
            return this.failing();
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.failing();
        }

        private Slice failing() {
            return (line, headers, body) -> {
                this.calls.incrementAndGet();
                return CompletableFuture.failedFuture(
                    new AssertionError("upstream must not be hit on cache HEAD")
                );
            };
        }
    }
}
