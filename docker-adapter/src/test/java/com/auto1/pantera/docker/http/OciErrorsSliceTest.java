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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsSame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * B81: errors produced in front of the Docker adapter (the anonymous-access
 * gate, the request-size limit) carry OCI error bodies and the same
 * challenge as the adapter's own 401.
 */
final class OciErrorsSliceTest {

    private static final RequestLine LINE =
        new RequestLine(RqMethod.GET, "/v2/docker-local/app/tags/list");

    @Test
    void rendersAnonymous401AsOciErrorWithFullChallenge() {
        final Response response = new OciErrorsSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.unauthorized()
                    .header(new WwwAuthenticate("Basic realm=\"pantera\""))
                    .build()
            )
        ).response(OciErrorsSliceTest.LINE, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "401 carries the OCI UNAUTHORIZED error",
            response, new IsErrorsResponse(RsStatus.UNAUTHORIZED, "UNAUTHORIZED")
        );
        MatcherAssert.assertThat(
            "challenge matches the credentialed-failure path",
            response.headers().values(WwwAuthenticate.NAME),
            new IsEqual<>(List.of("Basic realm=\"pantera\", Bearer realm=\"pantera\""))
        );
    }

    @Test
    void renders413AsOciError() {
        MatcherAssert.assertThat(
            new OciErrorsSlice(
                (line, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.payloadTooLarge().build()
                )
            ).response(OciErrorsSliceTest.LINE, Headers.EMPTY, Content.EMPTY).join(),
            new IsErrorsResponse(RsStatus.REQUEST_TOO_LONG, "SIZE_INVALID")
        );
    }

    @Test
    void keepsResponsesThatAlreadyHaveABody() {
        final Response original = ResponseBuilder.unauthorized()
            .jsonBody(new com.auto1.pantera.docker.error.UnauthorizedError().json())
            .build();
        MatcherAssert.assertThat(
            new OciErrorsSlice(
                (line, headers, body) -> CompletableFuture.completedFuture(original)
            ).response(OciErrorsSliceTest.LINE, Headers.EMPTY, Content.EMPTY).join(),
            new IsSame<>(original)
        );
    }
}
