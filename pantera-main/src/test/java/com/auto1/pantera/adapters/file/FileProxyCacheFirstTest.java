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
package com.auto1.pantera.adapters.file;

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.BaseCachedProxySlice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.EcsLoggingSlice;
import com.auto1.pantera.settings.StorageByAlias;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.test.TestStoragesCache;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileProxy} wires the repository storage into its slices, so cached
 * files are served cache-first and cache-only group probes can answer.
 *
 * @since 2.2.9
 */
final class FileProxyCacheFirstTest {

    /**
     * Payload.
     */
    private static final byte[] BODY = "file-body".getBytes();

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void servesStoredCopyWithoutUpstream(@TempDir final Path dir) throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final FileProxy proxy = new FileProxy(
            new CountingClients(calls), FileProxyCacheFirstTest.config(dir),
            Optional.empty(), NoopCooldownService.INSTANCE
        );
        final Response first = proxy.response(
            new RequestLine(RqMethod.GET, "/dist/a.bin"), Headers.EMPTY, Content.EMPTY
        ).join();
        final byte[] firstBody = first.body().asBytes();
        // The stream-through save lands asynchronously after the body is
        // consumed: poll (bounded) until the cache-only probe sees it.
        Response probe = FileProxyCacheFirstTest.probe(proxy);
        for (int attempt = 0; attempt < 500 && probe.status() != RsStatus.OK; ++attempt) {
            Thread.sleep(10L);
            probe = FileProxyCacheFirstTest.probe(proxy);
        }
        MatcherAssert.assertThat(
            "Cache-only probe finds the stored copy",
            probe.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "First GET is fetched from upstream",
            firstBody, new IsEqual<>(FileProxyCacheFirstTest.BODY)
        );
        MatcherAssert.assertThat(
            "Cache-only probe serves the stored copy",
            probe.body().asBytes(), new IsEqual<>(FileProxyCacheFirstTest.BODY)
        );
        MatcherAssert.assertThat(
            "Only the first GET reaches the upstream",
            calls.get(), new IsEqual<>(1)
        );
    }

    /**
     * Cache-only probe as the group resolver sends it to a circuit-open member.
     * @param proxy Proxy
     * @return Response
     */
    private static Response probe(final Slice proxy) {
        return proxy.response(
            new RequestLine(RqMethod.GET, "/dist/a.bin"),
            Headers.from(BaseCachedProxySlice.CACHE_ONLY_HEADER, "true")
                .copy().add(EcsLoggingSlice.INTERNAL_ROUTING_HEADER, "true"),
            Content.EMPTY
        ).join();
    }

    /**
     * File-proxy config with fs storage in the temp dir.
     * @param dir Storage directory
     * @return Repo config
     */
    private static RepoConfig config(final Path dir) {
        return RepoConfig.from(
            Yaml.createYamlMappingBuilder().add(
                "repo",
                Yaml.createYamlMappingBuilder()
                    .add("type", "file-proxy")
                    .add(
                        "remotes",
                        Yaml.createYamlSequenceBuilder().add(
                            Yaml.createYamlMappingBuilder()
                                .add("url", "http://upstream.test")
                                .build()
                        ).build()
                    )
                    .add(
                        "storage",
                        Yaml.createYamlMappingBuilder()
                            .add("type", "fs")
                            .add("path", dir.toString())
                            .build()
                    )
                    .build()
            ).build(),
            new StorageByAlias(Yaml.createYamlMappingBuilder().build()),
            new Key.From("files_proxy.yml"), new TestStoragesCache(), false
        );
    }

    /**
     * Client slices whose every upstream counts calls and serves {@link #BODY}.
     */
    private static final class CountingClients implements ClientSlices {

        /**
         * Call counter.
         */
        private final AtomicInteger calls;

        /**
         * Ctor.
         * @param calls Call counter
         */
        CountingClients(final AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Slice http(final String host) {
            return this.upstream();
        }

        @Override
        public Slice http(final String host, final int port) {
            return this.upstream();
        }

        @Override
        public Slice https(final String host) {
            return this.upstream();
        }

        @Override
        public Slice https(final String host, final int port) {
            return this.upstream();
        }

        /**
         * Counting upstream.
         * @return Slice
         */
        private Slice upstream() {
            return (line, headers, body) -> {
                this.calls.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(FileProxyCacheFirstTest.BODY).build()
                );
            };
        }
    }
}
