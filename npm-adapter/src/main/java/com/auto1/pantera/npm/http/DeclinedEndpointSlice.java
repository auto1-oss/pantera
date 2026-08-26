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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;
import javax.json.Json;

/**
 * Answers a fast, explicit 404 for an npm endpoint this registry declines to
 * implement, instead of a 5xx or a silently-empty 200.
 *
 * <p>npm clients retry any status &gt;= 500 for roughly 70 seconds, so an
 * unsupported feature answered with 5xx (or left unrouted, falling through to
 * a generic error) looks like a broken server rather than a registry that
 * simply does not offer the feature. {@link com.auto1.pantera.http.RsStatus#NOT_FOUND}
 * is non-retriable and the {@code X-Pantera-Reason} header plus JSON body let
 * clients, logs and dashboards tell "not implemented" apart from "genuinely
 * absent".</p>
 *
 * @since 2.3.0
 */
public final class DeclinedEndpointSlice implements Slice {

    /**
     * Human-readable feature name used in the error sentence.
     */
    private final String feature;

    /**
     * Documentation anchor pointing at the explanation.
     */
    private final String docs;

    /**
     * Ctor.
     * @param feature Human-readable feature name
     * @param docs Documentation anchor
     */
    public DeclinedEndpointSlice(final String feature, final String docs) {
        this.feature = feature;
        this.docs = docs;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return body.asBytesFuture().thenApply(
            ignored -> ResponseBuilder.notFound()
                .header("X-Pantera-Reason", "not_implemented")
                .jsonBody(
                    Json.createObjectBuilder()
                        .add(
                            "error",
                            String.format(
                                "%s is not implemented by this registry", this.feature
                            )
                        )
                        .add("reason", "not_implemented")
                        .add("docs", this.docs)
                        .build()
                        .toString()
                )
                .build()
        );
    }
}
