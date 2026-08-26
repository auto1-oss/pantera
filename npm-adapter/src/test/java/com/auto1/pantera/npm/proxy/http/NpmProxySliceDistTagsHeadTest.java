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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.metadata.NoopCooldownMetadataService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.npm.proxy.NpmProxy;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Minor 1 regression: {@code NpmProxySlice} adds {@code HEAD} to the
 * packument route, whose pattern is broad enough to swallow
 * {@code <pkg>/dist-tags} as a bogus {@code (pkg, ref)} pair when the
 * dedicated dist-tags passthrough route stays {@code GET}-only. A
 * {@code HEAD /<pkg>/dist-tags} must reach the upstream passthrough, not
 * 404 out of the packument route.
 */
final class NpmProxySliceDistTagsHeadTest {

    @Test
    void headDistTagsReachesThePassthroughNotThePackumentRoute() {
        final RecordingSlice remote = new RecordingSlice();
        final NpmProxySlice slice = new NpmProxySlice(
            "",
            new NpmProxy(new InMemoryStorage(), remote),
            Optional.empty(),
            "npm-proxy",
            "npm",
            NoopCooldownService.INSTANCE,
            NoopCooldownMetadataService.INSTANCE,
            remote
        );
        slice.response(
            new RequestLine(RqMethod.HEAD, "/pnpm/dist-tags"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "HEAD .../dist-tags must be forwarded upstream, not swallowed by the packument route",
            remote.invoked(),
            new IsEqual<>(true)
        );
    }

    /**
     * Records whether it was ever invoked, standing in both for
     * {@code NpmProxySlice}'s {@code remote} passthrough target and
     * {@link NpmProxy}'s own upstream client — neither is exercised by a
     * dist-tags request reaching the packument route (the bug this test
     * guards against), only by one reaching the passthrough.
     */
    private static final class RecordingSlice implements Slice {

        /**
         * Whether {@link #response} was called.
         */
        private volatile boolean invoked;

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.invoked = true;
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        }

        boolean invoked() {
            return this.invoked;
        }
    }
}
