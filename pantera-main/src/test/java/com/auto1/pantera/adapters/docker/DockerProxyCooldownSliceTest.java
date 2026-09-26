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
package com.auto1.pantera.adapters.docker;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.docker.cooldown.CooldownImageName;
import com.auto1.pantera.docker.misc.OfficialImageName;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Digest-addressed manifest path of {@link DockerProxyCooldownSlice}.
 *
 * @since 2.2.9
 */
final class DockerProxyCooldownSliceTest {

    /**
     * Manifest digest.
     */
    private static final String DIGEST =
        "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    /**
     * Manifest body.
     */
    private static final String MANIFEST = "{\"schemaVersion\":2}";

    @Test
    void evaluationFailureStillServesTheManifestBody() {
        final Slice slice = new DockerProxyCooldownSlice(
            new OneShotManifest(), "my-docker", "docker-proxy",
            new Failing(), new DockerProxyCooldownInspector(), null
        );
        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, "/v2/my-docker/nginx/manifests/" + DIGEST),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "fail-open answer is the upstream 200",
            resp.status().code(),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the buffered manifest body is served, not the drained original",
            new String(resp.body().asBytesFuture().join(), StandardCharsets.UTF_8),
            new IsEqual<>(MANIFEST)
        );
    }

    @Test
    void digestManifestKeysOnCanonicalImageName() {
        final Recording cooldown = new Recording();
        final Slice slice = new DockerProxyCooldownSlice(
            new OneShotManifest(), "my-docker", "docker-proxy", cooldown,
            new DockerProxyCooldownInspector(new OfficialImageName(true)), null,
            new CooldownImageName("my-docker", new OfficialImageName(true))
        );
        for (final String name : List.of("nginx", "library/nginx")) {
            slice.response(
                new RequestLine(RqMethod.GET, "/v2/my-docker/" + name + "/manifests/" + DIGEST),
                Headers.EMPTY, Content.EMPTY
            ).join().body().asBytesFuture().join();
        }
        MatcherAssert.assertThat(
            cooldown.artifacts,
            new IsEqual<>(List.of("library/nginx", "library/nginx"))
        );
    }

    /**
     * Upstream answering a manifest whose body can be read once only, as a
     * network stream can.
     */
    private static final class OneShotManifest implements Slice {
        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(Headers.from(
                        new Header("Docker-Content-Digest", DIGEST),
                        new Header("Last-Modified", "Mon, 21 Sep 2026 10:00:00 GMT"),
                        new Header(
                            "Content-Type",
                            "application/vnd.docker.distribution.manifest.v2+json"
                        )
                    ))
                    .body(new Content.OneTime(
                        new Content.From(MANIFEST.getBytes(StandardCharsets.UTF_8))
                    ))
                    .build()
            );
        }
    }

    /**
     * Cooldown whose evaluation fails.
     */
    private static final class Failing extends Recording {
        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            return CompletableFuture.failedFuture(new IllegalStateException("db down"));
        }
    }

    /**
     * Cooldown allowing everything and recording evaluated artifacts.
     */
    private static class Recording implements CooldownService {
        /**
         * Evaluated artifacts.
         */
        private final List<String> artifacts = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            this.artifacts.add(request.artifact());
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }

        @Override
        public CompletableFuture<Void> unblock(
            final String repoType, final String repoName,
            final String artifact, final String version, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unblockAll(
            final String repoType, final String repoName, final String actor
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CooldownBlock>> activeBlocks(
            final String repoType, final String repoName
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
