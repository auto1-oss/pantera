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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Track 5 Phase 2B acceptance test for the Maven {@code HeadProxySlice}:
 * on a cached artifact, the HEAD response must come from local storage
 * metadata without ever touching upstream. Cache miss still proxies.
 *
 * @since 2.2.0
 */
final class HeadProxySliceCacheFirstTest {

    @Test
    @DisplayName("HEAD on a cached jar returns 200 + Content-Length without touching upstream")
    void headHitServesLocalMetadata() {
        final String path = "/com/example/lib/1.0/lib-1.0.jar";
        final byte[] bytes = "cached-jar-bytes".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path.substring(1)), new Content.From(bytes)).join();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return CompletableFuture.failedFuture(
                new AssertionError("upstream must not be hit on cache HEAD")
            );
        };
        final HeadProxySlice slice = new HeadProxySlice(upstream, Optional.of(storage));
        final Response resp = slice.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "200 OK from local storage", resp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "zero upstream calls",
            upstreamCalls.get(), new IsEqual<>(0)
        );
    }

    @Test
    @DisplayName("HEAD on a cached jar exposes Last-Modified, agreeing with Content-Length "
        + "(WS4-maven.9 — fulfils the class javadoc's long-standing promise)")
    void headHitExposesLastModified() {
        final String path = "/com/example/lib/1.0/lib-1.0.jar";
        final byte[] bytes = "cached-jar-bytes".getBytes(StandardCharsets.UTF_8);
        final InMemoryStorage storage = new InMemoryStorage();
        storage.save(new Key.From(path.substring(1)), new Content.From(bytes)).join();
        final Slice upstream = (line, headers, body) -> CompletableFuture.failedFuture(
            new AssertionError("upstream must not be hit on cache HEAD")
        );
        final HeadProxySlice slice = new HeadProxySlice(upstream, Optional.of(storage));
        final Response resp = slice.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "200 OK from local storage", resp.status(), new IsEqual<>(RsStatus.OK)
        );
        MatcherAssert.assertThat(
            "Content-Length matches the cached byte length",
            resp.headers().single("Content-Length").getValue(),
            new IsEqual<>(String.valueOf(bytes.length))
        );
        MatcherAssert.assertThat(
            "exactly one Last-Modified header is present",
            resp.headers().values("Last-Modified").size(),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "Last-Modified is a valid RFC 1123 HTTP date",
            isValidHttpDate(resp.headers().single("Last-Modified").getValue()),
            new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("HEAD on a missing artifact falls through to upstream HEAD")
    void headMissDelegatesToUpstream() {
        final InMemoryStorage storage = new InMemoryStorage();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return ResponseBuilder.ok().completedFuture();
        };
        final HeadProxySlice slice = new HeadProxySlice(upstream, Optional.of(storage));
        final Response resp = slice.response(
            new RequestLine(RqMethod.HEAD, "/missing/lib/1.0/lib-1.0.jar"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "upstream called exactly once",
            upstreamCalls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "200 OK forwarded from upstream",
            resp.status(), new IsEqual<>(RsStatus.OK)
        );
    }

    @Test
    @DisplayName("HEAD with no storage falls back to pre-Track-5 pass-through")
    void headWithoutStoragePassesThrough() {
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final Slice upstream = (line, headers, body) -> {
            upstreamCalls.incrementAndGet();
            return ResponseBuilder.ok().completedFuture();
        };
        final HeadProxySlice slice = new HeadProxySlice(upstream);
        slice.response(
            new RequestLine(RqMethod.HEAD, "/anything"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "no-storage variant is pure pass-through",
            upstreamCalls.get(), new IsEqual<>(1)
        );
    }

    /**
     * @param value Candidate {@code Last-Modified} header value
     * @return True when the value parses as an RFC 1123 HTTP date
     */
    private static boolean isValidHttpDate(final String value) {
        try {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(value);
            return true;
        } catch (final DateTimeParseException ex) {
            return false;
        }
    }
}
