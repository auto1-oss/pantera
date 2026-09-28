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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.cooldown.GoCooldownResponseFactory;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsIterableContaining;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Go download path keys cooldown on the same canonical version
 * ({@code v1.2.3}) as {@code @v/list} and {@code @latest}, so one Go
 * version maps to exactly one cooldown row.
 *
 * @since 2.2.9
 */
final class CachedProxySliceCooldownKeyTest {

    /**
     * Module under test.
     */
    private static final String MODULE = "example.com/test";

    /**
     * Upstream fake recording requested paths.
     */
    private RecordingUpstream upstream;

    /**
     * Cooldown fake.
     */
    private ScriptedCooldown cooldown;

    /**
     * Inspector fake.
     */
    private RecordingInspector inspector;

    /**
     * Slice under test.
     */
    private CachedProxySlice slice;

    @BeforeEach
    void setUp() {
        CooldownResponseRegistry.instance()
            .register(new GoCooldownResponseFactory(), "go-proxy");
        this.upstream = new RecordingUpstream();
        this.cooldown = new ScriptedCooldown(Set.of("v1.0.0"));
        this.inspector = new RecordingInspector();
        this.slice = new CachedProxySlice(
            this.upstream,
            Cache.NOP,
            Optional.empty(),
            Optional.of(new InMemoryStorage()),
            "go-proxy-test",
            "go-proxy",
            this.cooldown,
            this.inspector
        );
    }

    @Test
    void zipDownloadHonoursBlockKeyedOnCanonicalVersion() {
        final Response resp = this.slice.response(
            new RequestLine(RqMethod.GET, "/" + MODULE + "/@v/v1.0.0.zip"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        MatcherAssert.assertThat(
            "block row keyed 'v1.0.0' (as @v/list / @latest key it) must block the .zip",
            resp.status(),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "a blocked download must not reach upstream",
            this.upstream.paths,
            new IsNot<>(new IsIterableContaining<>(new IsEqual<>("/" + MODULE + "/@v/v1.0.0.zip")))
        );
    }

    @Test
    void infoDownloadEvaluatesCanonicalVersionAndKeepsUpstreamPath() {
        final Response resp = this.slice.response(
            new RequestLine(RqMethod.GET, "/" + MODULE + "/@v/v2.0.0.info"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        resp.body().asBytesFuture().join();
        MatcherAssert.assertThat(
            "cooldown evaluated with the canonical 'v' version",
            this.cooldown.versions,
            new IsEqual<>(List.of("v2.0.0"))
        );
        MatcherAssert.assertThat(
            "publish date looked up under the canonical 'v' version",
            this.inspector.versions,
            new IsIterableContaining<>(new IsEqual<>("v2.0.0"))
        );
        MatcherAssert.assertThat(
            "upstream request path unchanged",
            this.upstream.paths,
            new IsIterableContaining<>(new IsEqual<>("/" + MODULE + "/@v/v2.0.0.info"))
        );
    }

    /**
     * Upstream that answers 200 to everything and records paths.
     */
    private static final class RecordingUpstream implements Slice {
        /**
         * Requested paths.
         */
        private final List<String> paths = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.paths.add(line.uri().getPath());
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .body("{}".getBytes(StandardCharsets.UTF_8))
                    .build()
            );
        }
    }

    /**
     * Cooldown blocking a fixed set of versions and recording requests.
     */
    private static final class ScriptedCooldown implements CooldownService {
        /**
         * Blocked versions.
         */
        private final Set<String> blocked;

        /**
         * Evaluated versions.
         */
        private final List<String> versions = new CopyOnWriteArrayList<>();

        ScriptedCooldown(final Set<String> blocked) {
            this.blocked = blocked;
        }

        @Override
        public CompletableFuture<CooldownResult> evaluate(
            final CooldownRequest request, final CooldownInspector insp
        ) {
            this.versions.add(request.version());
            if (!this.blocked.contains(request.version())) {
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
            return CompletableFuture.completedFuture(
                CooldownResult.blocked(
                    new CooldownBlock(
                        request.repoType(), request.repoName(), request.artifact(),
                        request.version(), CooldownReason.FRESH_RELEASE,
                        Instant.now(), Instant.now().plusSeconds(3_600), List.of()
                    )
                )
            );
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
     * Inspector recording looked-up versions.
     */
    private static final class RecordingInspector implements CooldownInspector {
        /**
         * Looked-up versions.
         */
        private final List<String> versions = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Optional<Instant>> releaseDate(
            final String artifact, final String version
        ) {
            this.versions.add(version);
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
