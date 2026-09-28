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
package com.auto1.pantera.adapters;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Method gate for proxy repositories: a proxy is read-only, so any method
 * other than GET, HEAD and the explicitly allowed extras (npm's audit POST)
 * answers {@code 405 Method Not Allowed} with an {@code Allow} header,
 * without contacting any upstream. Without it an upload to a proxy
 * surfaced as a bare 404, which reads as a wrong path rather than a
 * wrong repository.
 *
 * @since 2.2.9
 */
public final class ReadOnlyProxySlice implements Slice {

    /**
     * Proxy slice.
     */
    private final Slice origin;

    /**
     * Methods forwarded to the proxy.
     */
    private final Set<RqMethod> allowed;

    /**
     * Ctor.
     *
     * @param origin Proxy slice
     * @param extra Methods allowed in addition to GET and HEAD
     */
    public ReadOnlyProxySlice(final Slice origin, final RqMethod... extra) {
        this.origin = origin;
        this.allowed = EnumSet.of(RqMethod.GET, RqMethod.HEAD);
        this.allowed.addAll(java.util.List.of(extra));
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        if (this.allowed.contains(line.method())) {
            return this.origin.response(line, headers, body);
        }
        final String allow = this.allowed.stream()
            .map(RqMethod::value)
            .collect(Collectors.joining(", "));
        return body.asBytesFuture().thenApply(
            ignored -> ResponseBuilder.methodNotAllowed().header("Allow", allow).build()
        );
    }
}
