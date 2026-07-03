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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pins {@link ChecksumProxySlice}'s upstream-error propagation contract.
 *
 * <p>Before the Track 1 fix, ChecksumProxySlice fell through to its
 * compute-from-artifact branch on <em>any</em> non-success upstream response
 * — including 429. That had two failure modes:
 * <ol>
 *   <li>the artifact fetch hit the same rate-limit and looked like a
 *       checksum miss to the Maven client (404), masking the real signal;</li>
 *   <li>when the artifact <em>did</em> happen to be cached, the locally
 *       computed sha1 could be served while a corresponding cached primary
 *       was stale or partially-written, contributing to the
 *       "Checksum validation failed" warnings seen during slow resolves.</li>
 * </ol>
 *
 * <p>This suite locks in: only TRUE 404 on the upstream checksum file
 * triggers the compute-from-artifact fallback. Every other non-success
 * (429, 5xx, 403) propagates verbatim.
 *
 * @since 2.2.0
 */
final class ChecksumProxySliceErrorPropagationTest {

    @Test
    @Timeout(5)
    @DisplayName("upstream 429 on .sha1 request propagates as 429 (no fallback to compute)")
    void rateLimitedChecksumPropagates() throws Exception {
        final Slice upstream = (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "30")
                    .textBody("rate limited")
                    .build()
            );
        final Response resp = new ChecksumProxySlice(upstream).response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar.sha1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(2, TimeUnit.SECONDS);

        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @Timeout(5)
    @DisplayName("upstream 5xx on .sha1 request propagates as 5xx (no fallback to compute)")
    void serverErrorChecksumPropagates() throws Exception {
        final Slice upstream = (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.INTERNAL_ERROR)
                    .textBody("upstream broken")
                    .build()
            );
        final Response resp = new ChecksumProxySlice(upstream).response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar.sha1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(2, TimeUnit.SECONDS);

        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.INTERNAL_ERROR));
    }

    @Test
    @Timeout(5)
    @DisplayName("upstream 429 on artifact during compute-fallback propagates as 429")
    void rateLimitedArtifactDuringFallbackPropagates() throws Exception {
        // .sha1 returns 404 (would normally trigger fallback), the artifact
        // GET that the fallback then issues also returns 429 from upstream.
        // Before the fix that 429 was collapsed to 404 too.
        final Slice upstream = (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.endsWith(".sha1")) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.notFound().build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "5")
                    .build()
            );
        };
        final Response resp = new ChecksumProxySlice(upstream).response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar.sha1"),
            Headers.EMPTY,
            Content.EMPTY
        ).get(2, TimeUnit.SECONDS);

        MatcherAssert.assertThat(resp.status(), new IsEqual<>(RsStatus.TOO_MANY_REQUESTS));
    }
}
