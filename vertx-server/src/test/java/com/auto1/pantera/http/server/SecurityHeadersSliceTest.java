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
package com.auto1.pantera.http.server;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tests for {@link SecurityHeadersSlice}.
 *
 * @since 2.2.0
 */
class SecurityHeadersSliceTest {

    @Test
    void emitsAllSixSecurityHeadersOnPlainResponse() {
        final Response res = new SecurityHeadersSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            )
        ).response(
            RequestLine.from("GET / HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final Headers out = res.headers();
        MatcherAssert.assertThat(
            "HSTS header must be present on TLS-enabled deployments",
            singleHeader(out, "Strict-Transport-Security"),
            new IsEqual<>(SecurityHeadersSlice.HSTS_DEFAULT)
        );
        MatcherAssert.assertThat(
            "X-Content-Type-Options must be set to nosniff",
            singleHeader(out, "X-Content-Type-Options"),
            new IsEqual<>(SecurityHeadersSlice.CONTENT_TYPE_OPTIONS_DEFAULT)
        );
        MatcherAssert.assertThat(
            "X-Frame-Options must default to DENY",
            singleHeader(out, "X-Frame-Options"),
            new IsEqual<>(SecurityHeadersSlice.FRAME_OPTIONS_DEFAULT)
        );
        MatcherAssert.assertThat(
            "Referrer-Policy must default to strict-origin-when-cross-origin",
            singleHeader(out, "Referrer-Policy"),
            new IsEqual<>(SecurityHeadersSlice.REFERRER_POLICY_DEFAULT)
        );
        MatcherAssert.assertThat(
            "Content-Security-Policy must default to default-src 'self'",
            singleHeader(out, "Content-Security-Policy"),
            new IsEqual<>(SecurityHeadersSlice.CSP_DEFAULT)
        );
        MatcherAssert.assertThat(
            "Permissions-Policy must opt out of geolocation/microphone/camera",
            singleHeader(out, "Permissions-Policy"),
            new IsEqual<>(SecurityHeadersSlice.PERMISSIONS_DEFAULT)
        );
    }

    @Test
    void suppressesHstsOnPlainHttpListener() {
        final Response res = new SecurityHeadersSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok().build()
            ),
            false
        ).response(
            RequestLine.from("GET / HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "HSTS must NOT be emitted for plain-HTTP listeners",
            res.headers().find("Strict-Transport-Security").isEmpty(),
            new IsEqual<>(true)
        );
    }

    @Test
    void preservesDownstreamSuppliedFrameOptions() {
        // An adapter that needs to allow same-origin framing (e.g. the
        // admin UI) must be able to override the default DENY.
        final Slice inner = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok().header("X-Frame-Options", "SAMEORIGIN").build()
        );
        final Response res = new SecurityHeadersSlice(inner).response(
            RequestLine.from("GET /ui HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final List<Header> found = res.headers().find("X-Frame-Options");
        MatcherAssert.assertThat(
            "Downstream-supplied X-Frame-Options must be preserved exactly once",
            found.size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Downstream-supplied X-Frame-Options must NOT be replaced by DENY",
            found.getFirst().getValue(),
            new IsEqual<>("SAMEORIGIN")
        );
    }

    @Test
    void preservesDownstreamSuppliedContentSecurityPolicy() {
        final Slice inner = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Security-Policy", "default-src 'self' https://cdn.example.com")
                .build()
        );
        final Response res = new SecurityHeadersSlice(inner).response(
            RequestLine.from("GET /ui HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        final List<Header> found = res.headers().find("Content-Security-Policy");
        MatcherAssert.assertThat(
            "Downstream CSP must be preserved without duplication",
            found.size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Downstream CSP value must be preserved verbatim",
            found.getFirst().getValue(),
            new IsEqual<>("default-src 'self' https://cdn.example.com")
        );
    }

    @Test
    void responseStatusAndBodyArePassedThrough() {
        // The decorator must NOT touch status or body.
        final Slice inner = (line, headers, body) -> CompletableFuture.completedFuture(
            ResponseBuilder.notFound().textBody("missing").build()
        );
        final Response res = new SecurityHeadersSlice(inner).response(
            RequestLine.from("GET /missing HTTP/1.1"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            res.status().code(),
            new IsEqual<>(404)
        );
    }

    @Test
    void managedHeaderSetExposedForCallers() {
        MatcherAssert.assertThat(
            SecurityHeadersSlice.managedHeaders().size(),
            new IsEqual<>(6)
        );
    }

    private static String singleHeader(final Headers headers, final String name) {
        final List<Header> hits = headers.find(name);
        if (hits.size() != 1) {
            throw new AssertionError(
                "Expected exactly one '" + name + "' header but found " + hits.size()
            );
        }
        return hits.getFirst().getValue();
    }
}
