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
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * I4 regression coverage: repositories bound to a dedicated port
 * ({@code VertxMain#startRepos}, and the hot-reload handler on {@code
 * RepositoryEvents.ADDRESS}) are handed straight to a listener, bypassing
 * {@code MainSlice}'s {@code ApiRoutingSlice}/{@code SliceByPath} pipeline —
 * and therefore bypassing both of those slices' internal-header scrubs. This
 * is the wrapper {@code VertxMain} applies on that path instead; a
 * client-supplied {@link ClientBaseUrl#HEADER} (or {@link
 * ClientBaseUrl#ORIGINAL_PATH}) must not survive it.
 */
final class InternalHeaderScrubSliceTest {

    @Test
    void clientSuppliedClientBaseHeaderDoesNotSurvive() {
        MatcherAssert.assertThat(
            new ClientBaseUrl(this.observedHeaders(ClientBaseUrl.HEADER, "https://evil.example.com"))
                .stamped(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void clientSuppliedClientBaseHeaderDoesNotSurviveInLowercase() {
        // Headers.add(header, true)'s overwrite path compares names
        // case-sensitively, so the scrub itself must not rely on it either.
        MatcherAssert.assertThat(
            new ClientBaseUrl(this.observedHeaders("x-pantera-client-base", "https://evil.example.com"))
                .stamped(),
            new IsEqual<>(Optional.empty())
        );
    }

    @Test
    void clientSuppliedOriginalPathHeaderDoesNotSurvive() {
        final Headers observed = this.observedHeaders(
            ClientBaseUrl.ORIGINAL_PATH, "/npm_group/pnpm"
        );
        MatcherAssert.assertThat(
            observed.values(ClientBaseUrl.ORIGINAL_PATH).isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void ordinaryHeadersStillReachTheWrappedSlice() {
        final Headers observed = this.observedHeaders("Host", "reg.example.com");
        MatcherAssert.assertThat(
            observed.values("Host"),
            new IsEqual<>(List.of("reg.example.com"))
        );
    }

    /**
     * Drive {@link InternalHeaderScrubSlice} with a single header and
     * return what the wrapped slice observed.
     *
     * @param name Header name to send
     * @param value Header value to send
     * @return Headers the wrapped slice observed
     */
    private Headers observedHeaders(final String name, final String value) {
        final RecordingSlice recording = new RecordingSlice();
        new InternalHeaderScrubSlice(recording).response(
            new RequestLine(RqMethod.GET, "/npm_local/pnpm"),
            new Headers().add(name, value),
            Content.EMPTY
        ).join();
        return recording.lastHeaders();
    }

    /**
     * Records the headers the last {@link #response} call observed.
     */
    private static final class RecordingSlice implements Slice {

        /**
         * Headers observed by the last {@link #response} call.
         */
        private Headers lastHeaders;

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            this.lastHeaders = headers;
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        }

        Headers lastHeaders() {
            return this.lastHeaders;
        }
    }
}
