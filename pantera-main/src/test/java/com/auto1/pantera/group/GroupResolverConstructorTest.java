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
package com.auto1.pantera.group;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.rq.RequestLine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test proving the wiring-site-friendly {@link GroupResolver}
 * constructor builds a functional resolver and serves a happy-path 200
 * response.
 *
 * @since 2.2.0
 */
final class GroupResolverConstructorTest {

    private static final String GROUP = "maven-group";
    private static final String REPO_TYPE = "maven-group";
    private static final String MEMBER = "libs-release-local";

    @Test
    void newConstructor_buildsResolver_andServesHappyPath() {
        // SliceResolver that returns a trivial 200-OK slice for any member lookup.
        final Slice okSlice = (line, headers, body) ->
            CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        final SliceResolver resolver =
            (Key name, int port, int depth) -> okSlice;

        final NegativeCache negCache = new NegativeCache(
            "group-negative",
            GROUP,
            new NegativeCacheConfig(
                Duration.ofMinutes(5),
                10_000,
                false,
                NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
                NegativeCacheConfig.DEFAULT_L1_TTL,
                NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
                NegativeCacheConfig.DEFAULT_L2_TTL
            )
        );

        final GroupResolver groupResolver = new GroupResolver(
            resolver,
            GROUP,
            Collections.singletonList(MEMBER),
            8080,
            0,
            10L,
            Collections.emptyList(),
            Optional.empty(),
            Collections.emptySet(),
            REPO_TYPE,
            negCache,
            null,
            Runnable::run
        );

        final Response resp = groupResolver.response(
            new RequestLine("GET", "/foo"), Headers.EMPTY, Content.EMPTY
        ).join();

        assertEquals(200, resp.status().code(),
            "New wiring-site constructor must build a resolver that serves 200");
    }
}
