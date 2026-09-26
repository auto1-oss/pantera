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
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PathTraversalGuardSlice}.
 *
 * @since 2.2.9
 */
final class PathTraversalGuardSliceTest {

    @ParameterizedTest
    @CsvSource({
        "GET,/repo/../other/secret.txt",
        "PUT,/repo/../other/x.zip",
        "DELETE,/repo/a/../../b",
        "GET,/repo/%2e%2e/%2e%2e/etc/passwd",
        "PUT,/repo/%2E%2E/x.txt",
        "GET,/..",
        "HEAD,/repo/dir/.."
    })
    void rejectsParentSegments(final String method, final String path) {
        final AtomicBoolean reached = new AtomicBoolean();
        final Response rsp = new PathTraversalGuardSlice(
            (line, headers, body) -> {
                reached.set(true);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        ).response(new RequestLine(method, path), Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "a parent segment is a client error",
            rsp.status(), new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the origin is never reached",
            reached.get(), new IsEqual<>(false)
        );
    }

    @ParameterizedTest
    @CsvSource({
        "GET,/repo/a%00b",
        "PUT,/repo/col/cr%0D%0Ax.txt",
        "PUT,/test_prefix/api/repo/col/lf%0Ax.txt",
        "GET,/repo/tab%09x",
        "GET,/repo/del%7Fx"
    })
    void rejectsControlCharacters(final String method, final String path) {
        final AtomicBoolean reached = new AtomicBoolean();
        final Response rsp = new PathTraversalGuardSlice(
            (line, headers, body) -> {
                reached.set(true);
                return CompletableFuture.completedFuture(ResponseBuilder.ok().build());
            }
        ).response(new RequestLine(method, path), Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "a control character in the path is a client error",
            rsp.status(), new IsEqual<>(RsStatus.BAD_REQUEST)
        );
        MatcherAssert.assertThat(
            "the origin is never reached",
            reached.get(), new IsEqual<>(false)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/repo/com/acme/1.0/acme-1.0.jar",
        "/repo/file..txt",
        "/repo/..hidden/x",
        "/repo/a/.../b",
        "/repo/%252e%252e/x",
        "/repo/a/./b",
        "/repo/caf%C3%A9/x.txt",
        "/repo/a%20b.txt",
        "/"
    })
    void passesOrdinaryPaths(final String path) {
        final Response rsp = new PathTraversalGuardSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        ).response(new RequestLine("GET", path), Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(rsp.status(), new IsEqual<>(RsStatus.OK));
    }
}
