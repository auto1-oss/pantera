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
package com.auto1.pantera.npm.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RequestBodyTooLargeException;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.body.BoundedContent;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.json.Json;

/**
 * Caps the request body of the npm credential-bootstrap requests
 * ({@code npm login} / {@code npm adduser} and the declined web login).
 *
 * <p>These requests are reachable without credentials, so the body must be
 * bounded before any handler buffers it. A body declared (through
 * {@code Content-Length}) above {@link #MAX_BODY_BYTES} is refused without
 * being read; a streamed body is metered by {@link BoundedContent} and
 * cancelled the moment it crosses the cap. Both answer {@code 413} with a
 * JSON {@code error} body, and the wrapped slice never sees the credentials
 * of an oversized request.</p>
 *
 * @since 2.2.9
 */
public final class LoginBodyCapSlice implements Slice {

    /**
     * Cap on a credential-bootstrap body. A real npm login body (name,
     * password, email and a few CouchDB fields) is well under 1 KiB.
     */
    public static final long MAX_BODY_BYTES = 64L * 1024;

    /**
     * Wrapped slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Wrapped slice
     */
    public LoginBodyCapSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final CompletableFuture<Response> result;
        if (body.size().map(size -> size > LoginBodyCapSlice.MAX_BODY_BYTES).orElse(false)) {
            // Declared too large: refuse without subscribing to the body.
            result = CompletableFuture.completedFuture(LoginBodyCapSlice.tooLarge(line));
        } else {
            result = this.origin.response(
                line, headers, new BoundedContent(body, LoginBodyCapSlice.MAX_BODY_BYTES)
            ).handle(
                (response, error) -> {
                    if (error == null) {
                        return response;
                    }
                    if (RequestBodyTooLargeException.isCause(error)) {
                        return LoginBodyCapSlice.tooLarge(line);
                    }
                    if (error instanceof CompletionException completion) {
                        throw completion;
                    }
                    throw new CompletionException(error);
                }
            );
        }
        return result;
    }

    /**
     * The 413 answer for a body over the cap.
     * @param line Request line
     * @return Response
     */
    private static Response tooLarge(final RequestLine line) {
        EcsLogger.warn("com.auto1.pantera.npm")
            .message(
                String.format(
                    "npm login request refused: body exceeds %d bytes",
                    LoginBodyCapSlice.MAX_BODY_BYTES
                )
            )
            .eventCategory("authentication")
            .eventAction("npm_login")
            .eventOutcome("failure")
            .field("event.reason", "request_body_too_large")
            .field("url.path", line.uri().getPath())
            .field("http.response.status_code", 413)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.payloadTooLarge()
            .jsonBody(
                Json.createObjectBuilder()
                    .add("error", "login request body is too large")
                    .build()
            )
            .build();
    }
}
