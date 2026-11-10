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
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;

import java.util.List;

/**
 * {@code If-None-Match} → {@code 304 Not Modified} handling for artifact
 * bytes (WS4-maven.7). Local artifacts already advertise an {@code ETag}
 * (the sha1 sidecar, via {@link ArtifactHeaders}) but historically never
 * read the inbound conditional header back — every re-resolve of an
 * unchanged artifact re-downloaded the full body. Mirrors the pattern
 * {@code CachedProxySlice#buildMetadataResponse} already uses for
 * {@code maven-metadata.xml}.
 *
 * @since 2.3.0
 */
final class ArtifactConditionalGet {

    private ArtifactConditionalGet() {
    }

    /**
     * Whether the inbound {@code If-None-Match} matches the given ETag.
     * Only the first {@code If-None-Match} value is compared — Maven/Gradle
     * clients never send a comma-separated list for this header.
     *
     * @param inboundHeaders Client request headers
     * @param etag Server-computed ETag (sha1 hex, no quotes); may be
     *             {@code null}/empty when unknown, in which case this
     *             always returns {@code false} (never claim a match we
     *             can't back up)
     * @return True when the artifact is unchanged from the client's
     *         cached copy
     */
    static boolean matches(final Headers inboundHeaders, final String etag) {
        if (etag == null || etag.isEmpty()) {
            return false;
        }
        final List<String> values = inboundHeaders.values("If-None-Match");
        return !values.isEmpty() && etag.equals(values.get(0));
    }

    /**
     * Build the {@code 304 Not Modified} response: the {@code ETag} and
     * {@code Last-Modified} validators, no body.
     *
     * @param etag Server-computed ETag
     * @param lastModified {@code Last-Modified} header to echo back
     * @return 304 response
     */
    static Response notModified(final String etag, final Header lastModified) {
        return ResponseBuilder.from(RsStatus.NOT_MODIFIED)
            .header("ETag", etag)
            .header(lastModified)
            .build();
    }
}
