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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.api.v1.ArtifactDeletion;
import com.auto1.pantera.api.v1.StorageMetaCache;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.index.ArtifactDocument;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.index.SearchResult;
import com.auto1.pantera.test.RecordingIndex;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link NativeDeleteCascadeSlice}.
 *
 * @since 2.2.10
 */
final class NativeDeleteCascadeSliceTest {

    @Test
    void successfulDeleteRemovesTheIndexRowsOfThePath() {
        final RecordingIndex index = new RecordingIndex().row("repo", "a/b.whl");
        MatcherAssert.assertThat(
            "the adapter's status is kept",
            NativeDeleteCascadeSliceTest.send(
                NativeDeleteCascadeSliceTest.cascade(200, index), RqMethod.DELETE, "/a/b.whl"
            ),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the row of the deleted path is removed",
            List.of(index.removals(), index.rows().isEmpty()),
            new IsEqual<>(List.of(List.of("repo|a/b.whl"), true))
        );
    }

    @Test
    void failedDeleteAndOtherMethodsLeaveTheIndex() {
        final RecordingIndex index = new RecordingIndex().row("repo", "a/b.whl");
        MatcherAssert.assertThat(
            "a 404 delete answers 404",
            NativeDeleteCascadeSliceTest.send(
                NativeDeleteCascadeSliceTest.cascade(404, index), RqMethod.DELETE, "/a/b.whl"
            ),
            new IsEqual<>(404)
        );
        MatcherAssert.assertThat(
            "a GET answers the adapter's status",
            NativeDeleteCascadeSliceTest.send(
                NativeDeleteCascadeSliceTest.cascade(200, index), RqMethod.GET, "/a/b.whl"
            ),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "a root delete answers the adapter's status",
            NativeDeleteCascadeSliceTest.send(
                NativeDeleteCascadeSliceTest.cascade(200, index), RqMethod.DELETE, "/"
            ),
            new IsEqual<>(200)
        );
        MatcherAssert.assertThat(
            "the index is not touched",
            index.removals(), new IsEqual<>(List.of())
        );
    }

    @Test
    void failingCascadeKeepsTheAdapterStatus() {
        final ArtifactIndex broken = new ArtifactIndex() {
            @Override
            public CompletableFuture<Void> index(final ArtifactDocument doc) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Void> remove(final String repo, final String path) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Integer> removeByPath(final String repo, final String path) {
                return CompletableFuture.failedFuture(new IllegalStateException("db down"));
            }

            @Override
            public CompletableFuture<SearchResult> search(
                final String query, final int max, final int offset
            ) {
                return CompletableFuture.completedFuture(SearchResult.EMPTY);
            }

            @Override
            public CompletableFuture<List<String>> locate(final String path) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public void close() {
                // nothing to close
            }
        };
        MatcherAssert.assertThat(
            NativeDeleteCascadeSliceTest.send(
                NativeDeleteCascadeSliceTest.cascade(202, broken), RqMethod.DELETE, "/x.rpm"
            ),
            new IsEqual<>(202)
        );
    }

    /**
     * Cascade over an adapter answering a fixed status.
     * @param status Adapter status
     * @param index Index
     * @return Slice
     */
    private static Slice cascade(final int status, final ArtifactIndex index) {
        return new NativeDeleteCascadeSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.from(com.auto1.pantera.http.RsStatus.byCode(status)).build()
            ),
            "repo",
            new ArtifactDeletion(index, new StorageMetaCache())
        );
    }

    /**
     * Send a request.
     * @param slice Slice
     * @param method Method
     * @param path Path
     * @return Status code
     */
    private static int send(final Slice slice, final RqMethod method, final String path) {
        return slice.response(new RequestLine(method, path), Headers.EMPTY, Content.EMPTY)
            .join().status().code();
    }
}
