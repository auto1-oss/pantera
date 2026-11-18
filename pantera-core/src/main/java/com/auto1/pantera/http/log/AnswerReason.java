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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Why a non-2xx answer was given, carried on the answer itself.
 *
 * <p>The access record of a request is written by {@code EcsLoggingSlice},
 * the outermost slice, which sees a status and nothing of what led to it: a
 * 404 from the negative cache and a 404 of the upstream look the same there,
 * and so do a 502 after a lost connection and a 502 for a full bulkhead. The
 * slices that know the cause therefore put a reason token on the answer in
 * an internal header. A group relays the answer of a member with its reason;
 * the access-log slice writes the reason as {@code event.reason} and strips
 * the header before the answer leaves Pantera.</p>
 *
 * <p>A reason is a lower-case token ({@code negative_cache},
 * {@code upstream_status_404}, {@code connection_closed}, ...). Any other
 * value in the header is ignored: a proxied upstream could send it.</p>
 *
 * @since 2.2.12
 */
public final class AnswerReason {

    /**
     * Internal response header name. Never sent to clients.
     */
    public static final String HEADER = "X-Pantera-Ctx-Reason";

    /**
     * Shape of a reason token.
     */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]{1,64}");

    /**
     * Answer carrying (or not) the header.
     */
    private final Response response;

    /**
     * Ctor.
     * @param response Answer to read or stamp
     */
    public AnswerReason(final Response response) {
        this.response = response;
    }

    /**
     * The reason the answer carries.
     * @return Reason token, empty when there is none or it is not a token
     */
    public Optional<String> token() {
        return this.response.headers().values(AnswerReason.HEADER).stream()
            .filter(value -> AnswerReason.TOKEN.matcher(value).matches())
            .findFirst();
    }

    /**
     * The answer with its reason replaced by {@code token}, or removed when
     * {@code token} is {@code null}.
     * @param token Reason token, or {@code null}
     * @return Stamped answer
     */
    public Response stamped(final String token) {
        final Headers headers = new Headers(
            new ArrayList<>(
                this.response.headers().stream()
                    .filter(hdr -> !AnswerReason.HEADER.equalsIgnoreCase(hdr.getKey()))
                    .toList()
            )
        );
        if (token != null) {
            headers.add(AnswerReason.HEADER, token);
        }
        return new Response(this.response.status(), headers, this.response.body());
    }

    /**
     * The answer without the reason header.
     * @return Answer safe to send to the client
     */
    public Response stripped() {
        return this.stamped(null);
    }
}
