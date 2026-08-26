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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link DeclinedEndpointSlice}.
 */
final class DeclinedEndpointSliceTest {

    @Test
    void answersNotFoundWithAReasonHeader() {
        final Response response = new DeclinedEndpointSlice(
            "npm token management", "repositories/npm.md#unsupported-endpoints"
        ).response(
            new RequestLine(RqMethod.GET, "/-/npm/v1/tokens"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "uses a non-retriable status",
            response.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
        MatcherAssert.assertThat(
            "names the reason for logs and dashboards",
            response.headers().values("X-Pantera-Reason"),
            new IsEqual<>(java.util.List.of("not_implemented"))
        );
    }

    @Test
    void statusIsBelowFiveHundredSoClientsDoNotRetry() {
        final Response response = new DeclinedEndpointSlice("x", "y").response(
            new RequestLine(RqMethod.GET, "/-/npm/v1/hooks"), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            response.status().code() < 500, new IsEqual<>(true)
        );
    }

    @Test
    void consumesTheRequestBody() {
        final Content body = new Content.From("{}".getBytes(StandardCharsets.UTF_8));
        new DeclinedEndpointSlice("x", "y").response(
            new RequestLine(RqMethod.POST, "/-/npm/v1/tokens"), Headers.EMPTY, body
        ).join();
        MatcherAssert.assertThat(
            "body publisher was drained, not leaked",
            body.asBytesFuture().isDone(), new IsEqual<>(true)
        );
    }
}
