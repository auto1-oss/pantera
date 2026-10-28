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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.PanteraHttpException;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.UpstreamCircuitOpenException;
import com.auto1.pantera.http.log.FailureCause;
import com.auto1.pantera.http.log.ServerErrorLog;

/**
 * Turns an exception escaping an npm proxy request into the HTTP answer
 * (an exception must never reach the npm client as a dropped connection),
 * logging its cause once, correlated with the request.
 *
 * <p>The client body names no cause: exception text can carry internal host
 * names, addresses and paths, so a 5xx body is a fixed generic message and
 * the cause goes to the log only.</p>
 *
 * @since 2.2.11
 */
final class FailureResponse {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.auto1.pantera.npm";

    /**
     * ECS event.action of the failed request.
     */
    private final String action;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * Ctor.
     * @param action ECS event.action of the failed request
     * @param repo Repository name
     */
    FailureResponse(final String action, final String repo) {
        this.action = action;
        this.repo = repo;
    }

    /**
     * Log the failure and build the answer.
     * @param error Failure as caught (CompletionException-wrapped or not)
     * @param headers Request headers (correlation context)
     * @param path Request path
     * @return Response for the client
     */
    Response answer(final Throwable error, final Headers headers, final String path) {
        final FailureCause cause = new FailureCause(error);
        final Throwable root = cause.root();
        final Response response;
        if (root instanceof UpstreamCircuitOpenException circuit) {
            // A breaker fast-fail keeps its marker so the group resolver
            // can skip this member without convicting it.
            final ResponseBuilder rb = ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                .header(UpstreamCircuitOpenException.HEADER, "true")
                .jsonBody("{\"error\":\"Upstream circuit breaker is open\"}");
            if (circuit.retryAfterSeconds() > 0) {
                rb.header("Retry-After", Long.toString(circuit.retryAfterSeconds()));
            }
            response = rb.build();
        } else if (root instanceof PanteraHttpException http) {
            // A 4xx raised by Pantera's own code carries a client-facing
            // message; a 5xx never exposes one.
            final String text = http.status().serverError() || http.getMessage() == null
                ? "Upstream error" : http.getMessage();
            response = ResponseBuilder.from(http.status())
                .jsonBody(String.format("{\"error\":\"%s\"}", text.replace("\"", "\\\"")))
                .build();
        } else {
            response = ResponseBuilder.from(RsStatus.BAD_GATEWAY)
                .jsonBody("{\"error\":\"Upstream error\"}")
                .build();
        }
        final ServerErrorLog log = new ServerErrorLog(FailureResponse.LOGGER, this.action, headers)
            .repository(this.repo)
            .path(path)
            .status(response.status().code())
            .failure(error);
        if ("internal_error".equals(cause.reason()) && response.status().serverError()) {
            log.error("npm request failed");
        } else {
            // circuit_open is DEBUG: the breaker logs its own transitions.
            log.warnUnlessExpected("npm request failed");
        }
        return response;
    }
}
