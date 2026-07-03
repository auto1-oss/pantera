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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Phase E — the Maven proxy serves Pantera-owned validators only. Upstream
 * leakage headers (CF-*, X-Amz-*, X-Checksum-*, Age) must never appear on the
 * downstream response, and inbound {@code If-None-Match} matching the
 * computed ETag must produce a 304.
 *
 * @since 2.2.0
 */
class CachedProxySliceMetadataResponseTest {

    private static final byte[] BODY = "<metadata/>".getBytes(StandardCharsets.UTF_8);

    @Test
    void responseCarriesPanteraComputedEtagAndContentType() {
        final Response resp = CachedProxySlice.buildMetadataResponse(new Headers(), BODY);
        MatcherAssert.assertThat(
            "status", resp.status().code(), new IsEqual<>(200)
        );
        final List<String> etag = resp.headers().values("ETag");
        MatcherAssert.assertThat(
            "ETag header present", etag.isEmpty(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "ETag is weak", etag.get(0).startsWith("W/\""), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Content-Type carries charset",
            resp.headers().values("Content-Type").get(0),
            new IsEqual<>("application/xml; charset=utf-8")
        );
    }

    @Test
    void responseDoesNotLeakUpstreamValidatorHeaders() {
        final Response resp = CachedProxySlice.buildMetadataResponse(new Headers(), BODY);
        for (final String forbidden : new String[]{
            "X-Checksum-MD5", "X-Checksum-SHA1", "X-Checksum-SHA256",
            "CF-Cache-Status", "Age", "X-Amz-Cf-Id"
        }) {
            MatcherAssert.assertThat(
                forbidden + " must be absent",
                resp.headers().values(forbidden).isEmpty(),
                new IsEqual<>(true)
            );
        }
    }

    @Test
    void matchingIfNoneMatchProducesNotModified() {
        final Response firstResponse = CachedProxySlice.buildMetadataResponse(new Headers(), BODY);
        final String etag = firstResponse.headers().values("ETag").get(0);
        final Headers req = new Headers().add("If-None-Match", etag);
        final Response notModified = CachedProxySlice.buildMetadataResponse(req, BODY);
        MatcherAssert.assertThat(
            notModified.status().code(),
            new IsEqual<>(RsStatus.NOT_MODIFIED.code())
        );
    }
}
