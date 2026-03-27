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
package com.auto1.pantera.http.hm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import io.reactivex.Flowable;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for {@link RsHasBody}.
 */
final class RsHasBodyTest {

    @Test
    void shouldMatchEqualBody() {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From(
                Flowable.fromArray(
                    ByteBuffer.wrap("he".getBytes()),
                    ByteBuffer.wrap("ll".getBytes()),
                    ByteBuffer.wrap("o".getBytes())
                )
            ))
            .build();
        MatcherAssert.assertThat(
            "Matcher is expected to match response with equal body",
            new RsHasBody("hello".getBytes()).matches(response),
            new IsEqual<>(true)
        );
    }

    @Test
    void shouldNotMatchNotEqualBody() {
        final Response response = ResponseBuilder.ok()
            .body(new Content.From(Flowable.fromArray(ByteBuffer.wrap("1".getBytes()))))
            .build();
        MatcherAssert.assertThat(
            "Matcher is expected not to match response with not equal body",
            new RsHasBody("2".getBytes()).matches(response),
            new IsEqual<>(false)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"data", "chunk1,chunk2"})
    void shouldMatchResponseTwice(final String chunks) {
        final String[] elements = chunks.split(",");
        final byte[] data = String.join("", elements).getBytes();
        final Response response = ResponseBuilder.ok().body(
            Flowable.fromIterable(
                Stream.of(elements)
                    .map(String::getBytes)
                    .map(ByteBuffer::wrap)
                    .collect(Collectors.toList())
            )
        ).build();
        new RsHasBody(data).matches(response);
        Assertions.assertTrue(new RsHasBody(data).matches(response));
    }
}
