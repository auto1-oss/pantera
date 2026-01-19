/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

final class PathPrefixStripSliceTest {

    @Test
    void stripsMatchingPrefix() {
        final AtomicReference<String> captured = new AtomicReference<>();
        final Slice origin = capturePath(captured);
        final Slice slice = new PathPrefixStripSlice(origin, "simple");
        slice.response(
            new RequestLine(RqMethod.GET, "/simple/package/file.whl"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(captured.get(), new IsEqual<>("/package/file.whl"));
    }

    @Test
    void leavesNonMatchingPath() {
        final AtomicReference<String> captured = new AtomicReference<>();
        final Slice origin = capturePath(captured);
        final Slice slice = new PathPrefixStripSlice(origin, "simple");
        slice.response(
            new RequestLine(RqMethod.GET, "/package/file.whl"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(captured.get(), new IsEqual<>("/package/file.whl"));
    }

    @Test
    void stripsAnyConfiguredAlias() {
        final AtomicReference<String> captured = new AtomicReference<>();
        final Slice origin = capturePath(captured);
        final Slice slice = new PathPrefixStripSlice(origin, "simple", "direct-dists");
        slice.response(
            new RequestLine(RqMethod.GET, "/direct-dists/vendor/archive.zip?sha=1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(captured.get(), new IsEqual<>("/vendor/archive.zip?sha=1"));
    }

    private static Slice capturePath(final AtomicReference<String> target) {
        return (line, headers, body) -> {
            target.set(line.uri().getRawPath()
                + (line.uri().getRawQuery() != null ? '?' + line.uri().getRawQuery() : ""));
            return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
        };
    }
}
