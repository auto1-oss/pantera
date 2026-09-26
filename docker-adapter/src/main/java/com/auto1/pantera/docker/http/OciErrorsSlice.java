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
import com.auto1.pantera.docker.error.DockerError;
import com.auto1.pantera.docker.error.SizeInvalidError;
import com.auto1.pantera.docker.error.UnauthorizedError;
import com.auto1.pantera.docker.error.UnsupportedError;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.auth.BearerAuthScheme;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.WwwAuthenticate;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Gives the body-less errors produced in front of a Docker repository (the
 * anonymous-access gate's 401, the request-size limit's 413, a group's 405
 * for a push) the OCI error
 * body Docker clients parse, and gives every such 401 the same challenge
 * the adapter's own authentication advertises. Responses that already
 * carry a body pass through untouched.
 *
 * @since 2.2.9
 */
public final class OciErrorsSlice implements Slice {

    /**
     * Challenge advertised by the Docker adapter's authentication.
     */
    private static final String CHALLENGE = String.format(
        "%s realm=\"pantera\", %s realm=\"pantera\"",
        BasicAuthScheme.NAME, BearerAuthScheme.NAME
    );

    /**
     * Headers replaced when the body is rendered.
     */
    private static final Set<String> REPLACED = Set.of(
        "content-length", "content-type", WwwAuthenticate.NAME.toLowerCase(Locale.ROOT)
    );

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * @param origin Origin slice
     */
    public OciErrorsSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return this.origin.response(line, headers, body).thenApply(OciErrorsSlice::render);
    }

    /**
     * Render a body-less 401, 405 or 413 as an OCI error.
     *
     * @param response Origin response
     * @return Rendered response
     */
    private static Response render(final Response response) {
        final boolean empty = response.body().size().filter(size -> size == 0L).isPresent();
        final Response result;
        if (empty && response.status() == RsStatus.UNAUTHORIZED) {
            result = OciErrorsSlice.rebuild(response, new UnauthorizedError())
                .header(new WwwAuthenticate(OciErrorsSlice.CHALLENGE))
                .build();
        } else if (empty && response.status() == RsStatus.REQUEST_TOO_LONG) {
            result = OciErrorsSlice.rebuild(response, new SizeInvalidError()).build();
        } else if (empty && response.status() == RsStatus.METHOD_NOT_ALLOWED) {
            result = OciErrorsSlice.rebuild(response, new UnsupportedError()).build();
        } else {
            result = response;
        }
        return result;
    }

    /**
     * Copy the response status and headers, minus those the new body and
     * challenge replace, and set the error body.
     *
     * @param response Origin response
     * @param error Error to render
     * @return Builder
     */
    private static ResponseBuilder rebuild(final Response response, final DockerError error) {
        final Headers kept = new Headers();
        for (final Header header : response.headers()) {
            if (!OciErrorsSlice.REPLACED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                kept.add(header, false);
            }
        }
        return ResponseBuilder.from(response.status()).headers(kept).jsonBody(error.json());
    }
}
