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
import org.cactoos.map.MapEntry;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;

/**
 * Tests for {@link LoggingSlice}.
 */
class LoggingSliceTest {

    @Test
    void shouldLogRequestAndResponse() {
        new LoggingSlice(
            Level.INFO,
            new SliceSimple(
                ResponseBuilder.ok().header("Request-Header", "some; value").build()
            )
        ).response(
            RequestLine.from("GET /v2/ HTTP_1_1"),
            Headers.from(
                new MapEntry<>("Content-Length", "0"),
                new MapEntry<>("Content-Type", "whatever")
            ),
            Content.EMPTY
        ).join();
    }

    @Test
    void shouldLogAndPreserveExceptionInSlice() {
        final IllegalStateException error = new IllegalStateException("Error in slice");
        MatcherAssert.assertThat(
            Assertions.assertThrows(
                Throwable.class,
                () -> this.handle(
                    (line, headers, body) -> {
                        throw error;
                    }
                )
            ),
            new IsEqual<>(error)
        );
    }

    @Test
    void shouldLogAndPreserveExceptionInResponse() {
        final IllegalStateException error = new IllegalStateException("Error in response");
        MatcherAssert.assertThat(
            Assertions.assertThrows(
                Throwable.class,
                () -> this.handle(
                    (line, headers, body) -> {
                        throw error;
                    }
                )
            ),
            new IsEqual<>(error)
        );
    }

    private void handle(Slice slice) {
        new LoggingSlice(Level.INFO, slice)
            .response(RequestLine.from("GET /hello/ HTTP/1.1"), Headers.EMPTY, Content.EMPTY)
            .join();
    }
}
