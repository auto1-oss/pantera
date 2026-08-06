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
 * WS8 Bug B2 (routing half): the tarball/asset route was {@code GET}-only,
 * so a {@code HEAD} on a tarball URL fell through to the generic {@code
 * RtRule.FALLBACK} 404 stub -- which never touches {@code NpmProxy} at all.
 * Adding {@code HEAD} to the asset route must route it to {@link
 * DownloadAssetSlice} (which drives a real cache-check/upstream-fetch)
 * instead. See {@code CachedNpmProxySliceTest} for the more important half
 * of this bug: proving that even a 404 reaching the wrapper on a HEAD can
 * never poison the negative cache for a subsequent GET.
 */
final class NpmProxySliceHeadTarballTest {

    @Test
    void headTarballReachesTheAssetHandlerNotTheFallback() {
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
            new RequestLine(RqMethod.HEAD, "/head-tarball-pkg/-/head-tarball-pkg-1.0.0.tgz"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "HEAD on a tarball URL must drive a real cache-check/upstream-fetch "
                + "(reaching the remote on a cache miss), not the FALLBACK 404 stub "
                + "that never touches NpmProxy",
            remote.invoked(),
            new IsEqual<>(true)
        );
    }

    /**
     * Records whether it was ever invoked -- standing in for both {@code
     * NpmProxySlice}'s {@code remote} passthrough target and {@link
     * NpmProxy}'s own upstream client, neither of which is exercised by a
     * request that never leaves the FALLBACK stub (the bug this test
     * guards against).
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
