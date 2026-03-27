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

import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Test for {@link SliceOptional}.
 */
class SliceOptionalTest {

    @Test
    void returnsNotFoundWhenAbsent() {
        MatcherAssert.assertThat(
            new SliceOptional<>(
                Optional.empty(),
                Optional::isPresent,
                ignored -> new SliceSimple(ResponseBuilder.ok().build())
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/any")
            )
        );
    }

    @Test
    void returnsCreatedWhenConditionIsMet() {
        MatcherAssert.assertThat(
            new SliceOptional<>(
                Optional.of("abc"),
                Optional::isPresent,
                ignored -> new SliceSimple(ResponseBuilder.noContent().build())
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NO_CONTENT),
                new RequestLine(RqMethod.GET, "/abc")
            )
        );
    }

    @Test
    void appliesSliceFunction() {
        final String body = "Hello";
        MatcherAssert.assertThat(
            new SliceOptional<>(
                Optional.of(body),
                Optional::isPresent,
                hello -> new SliceSimple(
                    ResponseBuilder.ok().body(hello.orElseThrow().getBytes()).build()
                )
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(body.getBytes())
                ),
                new RequestLine(RqMethod.GET, "/hello")
            )
        );
    }

}
