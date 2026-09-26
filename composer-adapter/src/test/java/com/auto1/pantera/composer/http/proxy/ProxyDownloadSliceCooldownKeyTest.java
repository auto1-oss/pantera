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
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The dist download must key its cooldown evaluation exactly like the
 * metadata handlers do (lowercase {@code vendor/package}), so a block row
 * written by one path is the row the other path, and an unblock, sees.
 *
 * @since 2.2.9
 */
final class ProxyDownloadSliceCooldownKeyTest {

    @Test
    void cooldownKeyIsTheLowercasePackageName() {
        final RecordingCooldown cooldown = new RecordingCooldown();
        new ProxyDownloadSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            ),
            null, URI.create("https://upstream.example"), Optional.empty(),
            "composer-proxy", "php", new InMemoryStorage(), cooldown, new NoDates()
        ).response(
            new RequestLine(RqMethod.GET, "/dist/Acme/Widget/1.0.0.zip"),
            Headers.EMPTY, Content.EMPTY
        ).handle((response, error) -> response).join();
        MatcherAssert.assertThat(cooldown.artifacts, new IsEqual<>(List.of("acme/widget")));
    }

    /**
     * Records the artifact of every evaluation; always allows.
     */
    private static final class RecordingCooldown implements CooldownService {

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
