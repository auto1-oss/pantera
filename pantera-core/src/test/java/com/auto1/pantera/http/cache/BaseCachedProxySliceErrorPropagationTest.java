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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Pins the upstream-status propagation contract for {@link BaseCachedProxySlice}.
 *
 * <p>Before the Track 1 fix, any non-success upstream response that wasn't a 5xx
 * was collapsed to {@code 404 Not Found} by {@code handleNonSuccess} and a copy
 * also seeded into {@link NegativeCache} — which meant a transient upstream 429
 * (Maven Central pushing back under burst load) became a sticky 404 for the
 * negative-cache TTL window (default 24h). The corresponding bug also lived in
 * {@code fetchDirect}.
 *
 * <p>This suite locks in the corrected behavior:
 * <ul>
 *   <li><b>429</b> is propagated verbatim with the upstream {@code Retry-After}
 *       header so Maven Aether can back off properly;</li>
 *   <li><b>403</b> / <b>401</b> / <b>410</b> are propagated verbatim so the
 *       client sees the real upstream signal;</li>
 *   <li><b>5xx</b> is normalised to a clean {@code 503 Service Unavailable}
 *       (operators don't want upstream error pages bleeding through);</li>
 *   <li><b>404</b> still produces {@code 404} (regression check — the original
 *       semantics for true absence are unchanged).</li>
 * </ul>
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceErrorPropagationTest {

    private static final String ARTIFACT_PATH = "/com/example/foo/1.0/foo-1.0.jar";

    @Test
    @Timeout(10)
    @DisplayName("upstream 429 propagates as 429 with Retry-After preserved")
    void propagates429WithRetryAfter() throws Exception {
        final Slice upstream = (line, headers, content) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .textBody("rate limited")
                    .build()
            );
        final Response resp = newSlice(upstream).response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat(
            "status is 429, not collapsed to 404",
            resp.status(),
            new IsEqual<>(RsStatus.TOO_MANY_REQUESTS)
        );
        assertThat(
            "Retry-After header propagates so the client can back off",
            headerValue(resp, "Retry-After"),
            new IsEqual<>("60")
        );
    }

    @Test
    @Timeout(10)
    @DisplayName("upstream 403 propagates as 403 (auth signal must reach client)")
    void propagates403() throws Exception {
        final Slice upstream = (line, headers, content) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.FORBIDDEN)
                    .textBody("forbidden")
                    .build()
            );
        final Response resp = newSlice(upstream).response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat(resp.status(), new IsEqual<>(RsStatus.FORBIDDEN));
    }

    @Test
    @Timeout(10)
    @DisplayName("upstream 5xx normalises to 503 (no upstream error page leakage)")
    void upstream5xxBecomes503() throws Exception {
        final Slice upstream = (line, headers, content) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.INTERNAL_ERROR)
                    .textBody("internal upstream failure")
                    .build()
            );
        final Response resp = newSlice(upstream).response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat(resp.status(), new IsEqual<>(RsStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    @Timeout(10)
    @DisplayName("upstream 404 still produces 404 (regression check for true absence)")
    void upstream404StillProduces404() throws Exception {
        final Slice upstream = (line, headers, content) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        final Response resp = newSlice(upstream).response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat(resp.status(), new IsEqual<>(RsStatus.NOT_FOUND));
    }

    private static TestSlice newSlice(final Slice upstream) {
        final Storage storage = new InMemoryStorage();
        return new TestSlice(upstream, storage);
    }

    private static String headerValue(final Response resp, final String name) {
        for (final com.auto1.pantera.http.headers.Header h : resp.headers()) {
            if (h.getKey().equalsIgnoreCase(name)) {
                return h.getValue();
            }
        }
        return null;
    }

    /** Minimal subclass that makes every path cacheable + storage-backed. */
    private static final class TestSlice extends BaseCachedProxySlice {
        TestSlice(final Slice upstream, final Storage storage) {
            super(
                upstream,
                new FromStorageCache(storage),
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }
}
