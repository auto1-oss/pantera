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
package com.auto1.pantera.http.cache;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ConditionalRequest}.
 */
class ConditionalRequestTest {

    @Test
    void buildsIfNoneMatchHeader() {
        final Headers headers = ConditionalRequest.conditionalHeaders(
            Optional.of("\"abc123\""), Optional.empty()
        );
        assertThat(
            headers.stream()
                .filter(h -> "If-None-Match".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .orElse(""),
            equalTo("\"abc123\"")
        );
    }

    @Test
    void buildsIfModifiedSinceHeader() {
        final Headers headers = ConditionalRequest.conditionalHeaders(
            Optional.empty(), Optional.of("Sat, 15 Feb 2025 12:00:00 GMT")
        );
        assertThat(
            headers.stream()
                .filter(h -> "If-Modified-Since".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .map(Header::getValue)
                .orElse(""),
            equalTo("Sat, 15 Feb 2025 12:00:00 GMT")
        );
    }

    @Test
    void buildsBothHeaders() {
        final Headers headers = ConditionalRequest.conditionalHeaders(
            Optional.of("\"etag-val\""), Optional.of("Mon, 01 Jan 2024 00:00:00 GMT")
        );
        assertThat(
            headers.stream()
                .filter(h -> "If-None-Match".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .isPresent(),
            is(true)
        );
        assertThat(
            headers.stream()
                .filter(h -> "If-Modified-Since".equalsIgnoreCase(h.getKey()))
                .findFirst()
                .isPresent(),
            is(true)
        );
    }

    @Test
    void returnsEmptyHeadersWhenNoMetadata() {
        final Headers headers = ConditionalRequest.conditionalHeaders(
            Optional.empty(), Optional.empty()
        );
        assertThat(headers, equalTo(Headers.EMPTY));
    }

    @Test
    void extractsEtagFromHeaders() {
        final Headers headers = new Headers(List.of(
            new Header("Content-Type", "application/octet-stream"),
            new Header("ETag", "\"xyz789\"")
        ));
        assertThat(
            ConditionalRequest.extractEtag(headers),
            equalTo(Optional.of("\"xyz789\""))
        );
    }

    @Test
    void extractsEtagCaseInsensitive() {
        final Headers headers = new Headers(List.of(
            new Header("etag", "\"lowercase\"")
        ));
        assertThat(
            ConditionalRequest.extractEtag(headers),
            equalTo(Optional.of("\"lowercase\""))
        );
    }

    @Test
    void returnsEmptyWhenNoEtag() {
        final Headers headers = new Headers(List.of(
            new Header("Content-Type", "text/plain")
        ));
        assertThat(
            ConditionalRequest.extractEtag(headers),
            equalTo(Optional.empty())
        );
    }

    @Test
    void extractsLastModified() {
        final Headers headers = new Headers(List.of(
            new Header("Last-Modified", "Sat, 15 Feb 2025 12:00:00 GMT")
        ));
        assertThat(
            ConditionalRequest.extractLastModified(headers),
            equalTo(Optional.of("Sat, 15 Feb 2025 12:00:00 GMT"))
        );
    }

    @Test
    void returnsEmptyWhenNoLastModified() {
        assertThat(
            ConditionalRequest.extractLastModified(Headers.EMPTY),
            equalTo(Optional.empty())
        );
    }
}
