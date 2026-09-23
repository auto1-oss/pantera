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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Answers the legacy XML-RPC {@code pip search} call with an XML-RPC fault
 * on PyPI repositories that cannot search (proxy and group), and passes
 * every other request to the origin.
 *
 * <p>Without it the search {@code POST} got an empty {@code 405}, and pip
 * crashed with an {@code AssertionError} because it expects an XML-RPC
 * response. pypi.org itself answers search with a fault since it disabled
 * the API, so a fault is the response pip already knows how to report.</p>
 *
 * @since 2.2.9
 */
public final class SearchFaultSlice implements Slice {

    /**
     * XML-RPC fault body.
     */
    private static final byte[] FAULT = String.join(
        "\n",
        "<?xml version='1.0'?>",
        "<methodResponse>",
        "<fault>",
        "<value><struct>",
        "<member>",
        "<name>faultCode</name>",
        "<value><int>-32601</int></value>",
        "</member>",
        "<member>",
        "<name>faultString</name>",
        "<value><string>search is not supported on this repository;"
            + " search a hosted PyPI repository or use the Pantera UI</string></value>",
        "</member>",
        "</struct></value>",
        "</fault>",
        "</methodResponse>"
    ).getBytes(StandardCharsets.UTF_8);

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Ctor.
     * @param origin Origin slice for every non-search request
     */
    public SearchFaultSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final CompletableFuture<Response> result;
        if (line.method() == RqMethod.POST && SearchFaultSlice.isXml(headers)) {
            result = body.asBytesFuture().thenApply(
                ignored -> ResponseBuilder.ok()
                    .header(ContentType.mime("text/xml"))
                    .body(SearchFaultSlice.FAULT)
                    .build()
            );
        } else {
            result = this.origin.response(line, headers, body);
        }
        return result;
    }

    /**
     * Whether the request body is XML-RPC ({@code text/*}, the content type
     * pip search sends; uploads are {@code multipart/*}).
     * @param headers Request headers
     * @return True for a text body
     */
    private static boolean isXml(final Headers headers) {
        return headers.values("content-type").stream()
            .anyMatch(value -> value.toLowerCase(Locale.ROOT).startsWith("text/"));
    }
}
