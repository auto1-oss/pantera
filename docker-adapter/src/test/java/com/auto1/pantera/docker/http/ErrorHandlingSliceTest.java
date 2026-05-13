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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.docker.error.InvalidManifestException;
import com.auto1.pantera.docker.error.InvalidRepoNameException;
import com.auto1.pantera.docker.error.InvalidTagNameException;
import com.auto1.pantera.docker.error.UnsupportedError;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

/**
 * Tests for {@link ErrorHandlingSlice}.
 */
class ErrorHandlingSliceTest {

    @Test
    void shouldPassRequestUnmodified() {
        final RequestLine line = new RequestLine(RqMethod.GET, "/file.txt");
        final Header header = new Header("x-name", "some value");
        final byte[] body = "text".getBytes();
        new ErrorHandlingSlice(
            (rqline, rqheaders, rqbody) -> {
                MatcherAssert.assertThat(
                    "Request line unmodified",
                    rqline,
                    new IsEqual<>(line)
                );
                MatcherAssert.assertThat(
                    "Headers unmodified",
                    rqheaders,
                    Matchers.containsInAnyOrder(header)
                );
                MatcherAssert.assertThat(
                    "Body unmodified",
                    rqbody.asBytes(),
                    new IsEqual<>(body)
                );
                return ResponseBuilder.ok().completedFuture();
            }
        ).response(
            line, Headers.from(header), new Content.From(body)
        ).join();
    }

    @Test
    void shouldPassResponseUnmodified() {
        final Header header = new Header("x-name", "some value");
        final byte[] body = "text".getBytes();
        final Response response = new AuthClientSlice(
            (rsline, rsheaders, rsbody) -> ResponseBuilder.ok()
                .header(header).body(body).completedFuture(),
            Authenticator.ANONYMOUS
        ).response(new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY)
            .join();
        ResponseAssert.check(response, RsStatus.OK, body, header);
    }

    @ParameterizedTest
    @MethodSource("exceptions")
    void shouldHandleErrorInvalid(RuntimeException exception, RsStatus status, String code) {
        MatcherAssert.assertThat(
            new ErrorHandlingSlice(
                (line, headers, body) -> CompletableFuture.failedFuture(exception)
            ).response(
                new RequestLine(RqMethod.GET, "/"),
                Headers.EMPTY, Content.EMPTY
            ).join(),
            new IsErrorsResponse(status, code)
        );
    }

    @ParameterizedTest
    @MethodSource("exceptions")
    void shouldHandleSliceError(RuntimeException exception, RsStatus status, String code) {
        MatcherAssert.assertThat(
            new ErrorHandlingSlice(
                (line, headers, body) -> {
                    throw exception;
                }
            ).response(new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY).
                join(),
            new IsErrorsResponse(status, code)
        );
    }

    @Test
    void shouldPassSliceError() {
        final RuntimeException exception = new IllegalStateException();
        final ErrorHandlingSlice slice = new ErrorHandlingSlice(
            (line, headers, body) -> {
                throw exception;
            }
        );
        final Exception actual = Assertions.assertThrows(
            CompletionException.class,
            () -> slice
                .response(new RequestLine(RqMethod.GET, "/"), Headers.EMPTY, Content.EMPTY)
                .join(),
            "Exception not handled"
        );

        MatcherAssert.assertThat(
            "Original exception preserved",
            actual.getCause(),
            new IsEqual<>(exception)
        );
    }

    private static Stream<Arguments> exceptions() {
        final List<Arguments> plain = Stream.concat(
            Stream.of(
                new InvalidRepoNameException("repo name exception"),
                new InvalidTagNameException("tag name exception"),
                new InvalidManifestException("manifest exception"),
                new InvalidDigestException("digest exception")
            ).map(err -> Arguments.of(err, RsStatus.BAD_REQUEST, err.code())),
            Stream.of(
                Arguments.of(
                    new UnsupportedOperationException(),
                    RsStatus.METHOD_NOT_ALLOWED,
                    new UnsupportedError().code()
                )
            )
        ).toList();
        return Stream.concat(
            plain.stream(),
            plain.stream().map(Arguments::get).map(
                original -> Arguments.of(
                    new CompletionException((Throwable) original[0]),
                    original[1],
                    original[2]
                )
            )
        );
    }
}
