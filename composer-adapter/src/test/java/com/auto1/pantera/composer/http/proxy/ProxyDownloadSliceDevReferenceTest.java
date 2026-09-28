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
 * A dev branch moves: after upstream {@code dev-master} goes from commit A
 * to commit B, the proxy must serve B's dist for the B reference, not the
 * A zip it cached earlier under the same version name.
 *
 * @since 2.2.9
 */
final class ProxyDownloadSliceDevReferenceTest {

    private static final URI UPSTREAM = URI.create("https://upstream.example");

    @Test
    void metadataRewritePinsDevDistsToTheirReference() {
        final String out = new String(
            new MetadataUrlRewriter("http://pantera/php_proxy").rewrite(
                "{\"packages\":{\"acme/devpkg\":["
                    + "{\"version\":\"dev-master\",\"dist\":{\"url\":\"https://upstream.example/B.zip\","
                    + "\"reference\":\"bbbb\"}},"
                    + "{\"version\":\"1.0.0\",\"dist\":{\"url\":\"https://upstream.example/1.zip\","
                    + "\"reference\":\"cccc\"}}]}}"
            ),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "dev dist URL carries the reference",
            out.contains("\"http://pantera/php_proxy/dist/acme/devpkg/dev-master.zip?ref=bbbb\""),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "tagged dist URL is unchanged",
            out.contains("\"http://pantera/php_proxy/dist/acme/devpkg/1.0.0.zip\""),
            new IsEqual<>(true)
        );
    }

    @Test
    void movedBranchIsFetchedAgainInsteadOfServingTheOldZip() {
        final InMemoryStorage storage = new InMemoryStorage();
        // The A-build cached before the branch moved.
        storage.save(
            new Key.From("dist", "acme", "devpkg", "dev-master.zip"),
            new Content.From("code-A".getBytes(StandardCharsets.UTF_8))
        ).join();
        ProxyDownloadSliceDevReferenceTest.devMetadata(storage, "bbbb", "/B.zip");
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final Response resp = ProxyDownloadSliceDevReferenceTest.slice(storage, fetched).response(
            new RequestLine(RqMethod.GET, "/dist/acme/devpkg/dev-master.zip?ref=bbbb"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "the B dist is served",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8), new IsEqual<>("code-/B.zip")
        );
        MatcherAssert.assertThat(
            "the B dist was fetched from the upstream",
            fetched, new IsEqual<>(List.of("/B.zip"))
        );
    }

    @Test
    void cachedReferenceIsServedWithoutUpstream() {
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(
            new Key.From("dist", "acme", "devpkg", "dev-master@bbbb.zip"),
            new Content.From("code-B".getBytes(StandardCharsets.UTF_8))
        ).join();
        ProxyDownloadSliceDevReferenceTest.devMetadata(storage, "bbbb", "/B.zip");
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final Response resp = ProxyDownloadSliceDevReferenceTest.slice(storage, fetched).response(
            new RequestLine(RqMethod.GET, "/dist/acme/devpkg/dev-master.zip?ref=bbbb"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "cached B dist is served",
            new String(resp.body().asBytes(), StandardCharsets.UTF_8), new IsEqual<>("code-B")
        );
        MatcherAssert.assertThat("no upstream call", fetched.isEmpty(), new IsEqual<>(true));
    }

    @Test
    void referenceNoLongerInMetadataIsNotFetchedUnderAnotherCommit() {
        final InMemoryStorage storage = new InMemoryStorage();
        ProxyDownloadSliceDevReferenceTest.devMetadata(storage, "bbbb", "/B.zip");
        final List<String> fetched = new CopyOnWriteArrayList<>();
        final Response resp = ProxyDownloadSliceDevReferenceTest.slice(storage, fetched).response(
            new RequestLine(RqMethod.GET, "/dist/acme/devpkg/dev-master.zip?ref=aaaa"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "an unknown reference is not served", resp.status().code(), new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "the current commit's zip is not stored under the old reference",
            fetched.isEmpty(), new IsEqual<>(true)
        );
    }

    private static void devMetadata(
        final InMemoryStorage storage, final String reference, final String path
    ) {
        storage.save(
            new Key.From("acme/devpkg~dev.json"),
            new Content.From(String.format(
                "{\"packages\":{\"acme/devpkg\":[{\"version\":\"dev-master\",\"dist\":{"
                    + "\"url\":\"http://pantera/php_proxy/dist/acme/devpkg/dev-master.zip?ref=%s\","
                    + "\"original_url\":\"https://upstream.example%s\",\"reference\":\"%s\"}}]}}",
                reference, path, reference
            ).getBytes(StandardCharsets.UTF_8))
        ).join();
    }

    private static ProxyDownloadSlice slice(
        final InMemoryStorage storage, final List<String> fetched
    ) {
        final Slice upstream = (line, headers, body) -> {
            fetched.add(line.uri().getPath());
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().textBody("code-" + line.uri().getPath()).build()
            );
        };
        return new ProxyDownloadSlice(
            upstream, null, UPSTREAM, Optional.empty(), "composer-proxy",
            "composer-proxy", storage, NoopCooldownService.INSTANCE, new NoDates()
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
