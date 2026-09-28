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
package com.auto1.pantera.docker.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseFactory;
import com.auto1.pantera.docker.cache.DockerProxyCooldownInspector;
import com.auto1.pantera.docker.misc.OfficialImageName;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.Every;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Docker cooldown keys: one image, one cooldown artifact, whatever the
 * client spelling, and release dates found under that same key.
 *
 * @since 2.2.9
 */
final class DockerCooldownKeyTest {

    /**
     * Manifest digest served by the fake upstream.
     */
    private static final String DIGEST =
        "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    /**
     * Pantera repository name.
     */
    private static final String REPO = "my-docker";

    /**
     * Canonical naming for a Docker Hub proxy.
     */
    private static final CooldownImageName HUB =
        new CooldownImageName(REPO, new OfficialImageName(true));

    @Test
    void manifestByTagKeysBothSpellingsOnOneArtifact() {
        final Recording cooldown = new Recording(Set.of());
        final Upstream upstream = new Upstream();
        final DockerManifestByTagHandler handler = new DockerManifestByTagHandler(
            upstream, cooldown, new NoDates(), "docker-proxy", REPO, HUB,
            ManifestReleaseRecorder.NONE
        );
        for (final String path : List.of(
            "/v2/my-docker/nginx/manifests/1.27",
            "/v2/my-docker/library/nginx/manifests/1.27"
        )) {
            handler.handle(
                new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY, "alice"
            ).join().body().asBytesFuture().join();
        }
        MatcherAssert.assertThat(
            "tag and digest of both spellings evaluated under library/nginx",
            cooldown.artifacts,
            new Every<>(new IsEqual<>("library/nginx"))
        );
        MatcherAssert.assertThat(
            "four evaluations (tag + digest per request)",
            cooldown.artifacts.size(),
            new IsEqual<>(4)
        );
        MatcherAssert.assertThat(
            "upstream paths are not rewritten",
            upstream.paths,
            new IsEqual<>(List.of(
                "/v2/my-docker/nginx/manifests/1.27",
                "/v2/my-docker/library/nginx/manifests/1.27"
            ))
        );
    }

    @Test
    void tagsListKeysOnCanonicalArtifact() {
        final Recording cooldown = new Recording(Set.of());
        final DockerTagsListHandler handler = new DockerTagsListHandler(
            new Upstream(), cooldown, new NoDates(), "docker-proxy", REPO, HUB
        );
        handler.handle(
            new RequestLine(RqMethod.GET, "/v2/my-docker/nginx/tags/list"), Headers.EMPTY, "alice"
        ).join().body().asBytesFuture().join();
        MatcherAssert.assertThat(
            cooldown.artifacts,
            new IsEqual<>(List.of("library/nginx"))
        );
    }

    @Test
    void nonHubUpstreamKeepsSingleSegmentName() {
        MatcherAssert.assertThat(
            new CooldownImageName(REPO, new OfficialImageName(false)).of("my-docker/nginx"),
            new IsEqual<>("nginx")
        );
    }

    @Test
    void inspectorFindsDatesRecordedUnderTheClientSpelling() {
        final DockerProxyCooldownInspector inspector =
            new DockerProxyCooldownInspector(new OfficialImageName(true));
        final List<String> persisted = new ArrayList<>();
        inspector.setReleaseDateCallback((artifact, version, release) -> persisted.add(artifact));
        final Instant released = Instant.parse("2026-09-20T10:00:00Z");
        // CacheManifests records under the trimmed client spelling.
        inspector.recordRelease("nginx", "1.27", released);
        MatcherAssert.assertThat(
            "cooldown lookup under the canonical name finds the date",
            inspector.releaseDate("library/nginx", "1.27").join(),
            new IsEqual<>(Optional.of(released))
        );
        MatcherAssert.assertThat(
            "the date is persisted under the canonical name",
            persisted,
            new IsEqual<>(List.of("library/nginx"))
        );
    }

    @Test
    void releaseDateIsRecordedBeforeTheTagIsEvaluated() {
        final AtomicBoolean recorded = new AtomicBoolean();
        final List<Boolean> seen = new CopyOnWriteArrayList<>();
        final CooldownService cooldown = new Recording(Set.of()) {
            @Override
            public CompletableFuture<CooldownResult> evaluate(
                final CooldownRequest request, final CooldownInspector inspector
            ) {
                seen.add(recorded.get());
                return super.evaluate(request, inspector);
            }
        };
        final DockerManifestByTagHandler handler = new DockerManifestByTagHandler(
            new Upstream(), cooldown, new NoDates(), "docker-proxy", REPO, HUB,
            (name, artifact, reference, digest, headers, manifest) -> {
                recorded.set(true);
                return CompletableFuture.completedFuture(null);
            }
        );
        handler.handle(
            new RequestLine(RqMethod.GET, "/v2/my-docker/nginx/manifests/1.27"),
            Headers.EMPTY, Content.EMPTY, "alice"
        ).join().body().asBytesFuture().join();
        MatcherAssert.assertThat(
            seen,
            new IsEqual<>(List.of(true, true))
        );
    }

    @Test
    void blockedTagAnswerCarriesTheCooldownMarker() {
        final DockerManifestByTagHandler handler = new DockerManifestByTagHandler(
            new Upstream(), new Recording(Set.of("1.27")), new NoDates(), "docker-proxy", REPO, HUB,
            ManifestReleaseRecorder.NONE
        );
        final Response resp = handler.handle(
            new RequestLine(RqMethod.GET, "/v2/my-docker/nginx/manifests/1.27"),
            Headers.EMPTY, Content.EMPTY, "alice"
        ).join();
        resp.body().asBytesFuture().join();
        MatcherAssert.assertThat(
            "blocked tag answers 404 MANIFEST_UNKNOWN",
            resp.status().code(),
            new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "GroupResolver marker present",
            resp.headers().values(CooldownResponseFactory.HEADER),
            new IsEqual<>(List.of("blocked"))
        );
    }

    /**
     * Upstream serving a manifest (with digest) for any manifest path and a
     * one-tag list for any tags/list path.
     */
    private static final class Upstream implements Slice {
        /**
         * Requested paths.
         */
        private final List<String> paths = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final String path = line.uri().getPath();
            this.paths.add(path);
            if (path.endsWith("/tags/list")) {
                return ResponseBuilder.ok()
                    .jsonBody("{\"name\":\"nginx\",\"tags\":[\"1.27\"]}")
                    .completedFuture();
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .headers(Headers.from(
                        new Header("Docker-Content-Digest", DIGEST),
                        new Header(
                            "Content-Type",
                            "application/vnd.docker.distribution.manifest.v2+json"
                        )
                    ))
                    .body("{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        }
    }

    /**
     * Cooldown recording evaluated artifacts; blocks the listed versions.
     */
    private static class Recording implements CooldownService {
        /**
         * Evaluated artifact names.
         */
        private final List<String> artifacts = new CopyOnWriteArrayList<>();

        /**
         * Blocked versions.
         */
        private final Set<String> blocked;

        Recording(final Set<String> blocked) {
            this.blocked = blocked;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector inspector
        ) {
            this.artifacts.add(request.artifact());
            if (!this.blocked.contains(request.version())) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            return CompletableFuture.completedFuture(CooldownResult.blocked(
                new CooldownBlock(
                    request.repoType(), request.repoName(), request.artifact(),
                    request.version(), CooldownReason.FRESH_RELEASE, Instant.now(),
                    Instant.now().plusSeconds(3_600), List.of()
                )
            ));
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

    /**
     * Inspector without dates.
     */
    private static final class NoDates implements CooldownInspector {
        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<List<com.auto1.pantera.cooldown.api.CooldownDependency>> dependencies(
            final String artifact, final String version
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
