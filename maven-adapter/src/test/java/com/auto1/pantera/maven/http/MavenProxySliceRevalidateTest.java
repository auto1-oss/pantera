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
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.ProxyMetadataRevalidators;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The maven proxy registers a raw-metadata revalidation hook: after it
 * runs, the next maven-metadata.xml read goes back to the upstream instead
 * of the metadata cache.
 *
 * @since 2.2.9
 */
final class MavenProxySliceRevalidateTest {

    @Test
    void revalidationSendsTheNextMetadataReadUpstream() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final String repo = "maven_proxy_" + UUID.randomUUID();
        final Slice proxy = new MavenProxySlice(
            new CountingClients(calls),
            URI.create("https://repo.example.com/maven2"),
            Authenticator.ANONYMOUS,
            Cache.NOP,
            Optional.empty(),
            repo,
            "maven-proxy",
            NoopCooldownService.INSTANCE,
            Optional.of(new InMemoryStorage())
        );
        MavenProxySliceRevalidateTest.read(proxy);
        MavenProxySliceRevalidateTest.read(proxy);
        final int cached = calls.get();
        final String outcome = ProxyMetadataRevalidators.instance().forRepo(repo)
            .orElseThrow().revalidate("com.example:lib").get(5, TimeUnit.SECONDS);
        MavenProxySliceRevalidateTest.read(proxy);
        MatcherAssert.assertThat("hook outcome", outcome, new IsEqual<>("invalidated"));
        MatcherAssert.assertThat(
            "the second read was a metadata-cache hit",
            cached, new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "after revalidation the read goes upstream again",
            calls.get(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "a name that addresses no artifact is refused",
            ProxyMetadataRevalidators.instance().forRepo(repo).orElseThrow()
                .revalidate("lib").get(5, TimeUnit.SECONDS),
            new IsEqual<>("unsupported_name")
        );
    }

    /**
     * GET the artifact's metadata, consuming the body.
     *
     * @param proxy Proxy slice
     */
    private static void read(final Slice proxy) {
        proxy.response(
            new RequestLine(RqMethod.GET, "/com/example/lib/maven-metadata.xml"),
            Headers.EMPTY, Content.EMPTY
        ).thenCompose(resp -> resp.body().asBytesFuture()).join();
    }

    /**
     * Client slices answering every request with a metadata document.
     *
     * @since 2.2.9
     */
    private static final class CountingClients implements ClientSlices {

        /**
         * Upstream call counter.
         */
        private final AtomicInteger calls;

        /**
         * Ctor.
         *
         * @param calls Counter
         */
        CountingClients(final AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Slice http(final String host) {
            return this.slice();
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.slice();
        }

        @Override
        public Slice https(final String host) {
            return this.slice();
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.slice();
        }

        /**
         * Upstream answering metadata.
         *
         * @return Slice
         */
        private Slice slice() {
            return (line, headers, body) -> {
                this.calls.incrementAndGet();
                return ResponseBuilder.ok()
                    .header("Content-Type", "application/xml")
                    .body(
                        ("<metadata><groupId>com.example</groupId><artifactId>lib</artifactId>"
                            + "<versioning><versions><version>1.0</version></versions>"
                            + "</versioning></metadata>").getBytes(StandardCharsets.UTF_8)
                    )
                    .completedFuture();
            };
        }
    }
}
