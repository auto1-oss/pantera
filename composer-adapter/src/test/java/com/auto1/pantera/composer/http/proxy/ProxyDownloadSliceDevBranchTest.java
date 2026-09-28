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
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Dev-branch dists: Composer v2 serves dev versions from
 * {@code /p2/<pkg>~dev.json}, which the proxy caches as
 * {@code <pkg>~dev.json}. The dist download must find the version's
 * original URL there, not only in the stable {@code <pkg>.json}.
 *
 * @since 2.2.9
 */
final class ProxyDownloadSliceDevBranchTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example");

    @Test
    void devBranchDistIsResolvedFromTheDevMetadataFile() {
        final InMemoryStorage storage = new InMemoryStorage();
        ProxyDownloadSliceDevBranchTest.save(storage, "acme/widget.json", "1.0.0",
            "https://upstream.example/dist/acme/widget/1.0.0.zip");
        ProxyDownloadSliceDevBranchTest.save(storage, "acme/widget~dev.json", "dev-main",
            "https://upstream.example/dist/acme/widget/dev-main.zip");
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final Response served = ProxyDownloadSliceDevBranchTest.slice(storage, fetched).response(
            new RequestLine(RqMethod.GET, "/dist/acme/widget/dev-main.zip"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "dev-branch dist is served", served.status().code(), new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "fetched from the URL in the dev metadata file",
            fetched, new IsEqual<>(List.of("/dist/acme/widget/dev-main.zip"))
        );
    }

    @Test
    void stableDistStillResolvesFromTheStableFile() {
        final InMemoryStorage storage = new InMemoryStorage();
        ProxyDownloadSliceDevBranchTest.save(storage, "acme/widget.json", "1.0.0",
            "https://upstream.example/dist/acme/widget/1.0.0.zip");
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final Response served = ProxyDownloadSliceDevBranchTest.slice(storage, fetched).response(
            new RequestLine(RqMethod.GET, "/dist/acme/widget/1.0.0.zip"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat("stable dist is served", served.status().code(), new IsEqual<>(200));
        MatcherAssert.assertThat(
            "fetched from the stable URL",
            fetched, new IsEqual<>(List.of("/dist/acme/widget/1.0.0.zip"))
        );
    }

    private static ProxyDownloadSlice slice(final InMemoryStorage storage, final List<String> fetched) {
        final Slice upstream = (line, headers, body) -> {
            fetched.add(line.uri().getPath());
            return CompletableFuture.completedFuture(ResponseBuilder.ok().textBody("zip").build());
        };
        return new ProxyDownloadSlice(
            upstream, null, UPSTREAM, Optional.empty(), "composer-proxy",
            "composer-proxy", storage, NoopCooldownService.INSTANCE, new NoDates()
        );
    }

    private static void save(
        final InMemoryStorage storage, final String key, final String version, final String url
    ) {
        storage.save(
            new Key.From(key),
            new Content.From(String.format(
                "{\"packages\":{\"acme/widget\":{\"%s\":{\"version\":\"%s\",\"dist\":{\"url\":\"%s\"}}}}}",
                version, version, url
            ).getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    /**
     * Inspector with no release dates (cooldown is a no-op here anyway).
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
