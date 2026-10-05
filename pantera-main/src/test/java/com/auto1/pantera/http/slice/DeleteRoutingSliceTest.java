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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link DeleteRoutingSlice}.
 *
 * @since 2.2.10
 */
final class DeleteRoutingSliceTest {

    /**
     * Adapter stand-in: answers 200.
     */
    private static final Slice ORIGIN = (line, headers, body) ->
        ResponseBuilder.ok().completedFuture();

    /**
     * Delete stand-in: answers 204.
     */
    private static final Slice DELETE = (line, headers, body) ->
        ResponseBuilder.noContent().completedFuture();

    @Test
    void routesDeleteToTheDeleteSlice() {
        MatcherAssert.assertThat(
            DeleteRoutingSliceTest.status(
                new DeleteRoutingSlice(DeleteRoutingSliceTest.ORIGIN, DeleteRoutingSliceTest.DELETE),
                RqMethod.DELETE, "/a/b.bin"
            ),
            new IsEqual<>(204)
        );
    }

    @Test
    void keepsOtherMethodsOnTheAdapter() {
        final Slice slice = new DeleteRoutingSlice(
            DeleteRoutingSliceTest.ORIGIN, DeleteRoutingSliceTest.DELETE
        );
        for (final RqMethod method : new RqMethod[] {RqMethod.GET, RqMethod.PUT, RqMethod.HEAD}) {
            MatcherAssert.assertThat(
                String.format("%s reaches the adapter", method),
                DeleteRoutingSliceTest.status(slice, method, "/a/b.bin"),
                new IsEqual<>(200)
            );
        }
    }

    @Test
    void keepsNativeDeletePathsOnTheAdapter() {
        final Slice slice = new DeleteRoutingSlice(
            DeleteRoutingSliceTest.ORIGIN, DeleteRoutingSliceTest.DELETE,
            path -> path.endsWith(".tgz")
        );
        MatcherAssert.assertThat(
            "a path the generic delete handles",
            DeleteRoutingSliceTest.status(slice, RqMethod.DELETE, "/pkg/-/pkg-1.0.0.tgz"),
            new IsEqual<>(204)
        );
        MatcherAssert.assertThat(
            "a native delete path reaches the adapter",
            DeleteRoutingSliceTest.status(slice, RqMethod.DELETE, "/pkg/-rev/1-abc"),
            new IsEqual<>(200)
        );
    }

    /**
     * Status of a request.
     * @param slice Slice
     * @param method Method
     * @param path Path
     * @return Status code
     */
    private static int status(final Slice slice, final RqMethod method, final String path) {
        return slice.response(new RequestLine(method, path), Headers.EMPTY, Content.EMPTY)
            .join().status().code();
    }
}
