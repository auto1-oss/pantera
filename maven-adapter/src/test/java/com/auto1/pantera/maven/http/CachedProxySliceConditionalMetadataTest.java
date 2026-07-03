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
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.cache.ProxyCacheConfig;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice-level test for T-P10: {@code CachedProxySlice.handleMetadata} must
 * send {@code If-None-Match} / {@code If-Modified-Since} headers on the
 * second upstream call so a 304 short-circuits the blob rewrite.
 *
 * <p>The test uses a mock upstream Slice that captures the inbound headers
 * on each call and serves a deterministic response sequence:
 * <ol>
 *   <li>First call: 200 OK with bytes + {@code ETag} + {@code Last-Modified}.</li>
 *   <li>Second call (after soft TTL elapses): captured headers must include
 *       {@code If-None-Match} and {@code If-Modified-Since}; we serve 304.</li>
 * </ol>
 * The test then verifies the cached body bytes are byte-for-byte
 * identical, proving the 304 path did not rewrite the blob.
 */
final class CachedProxySliceConditionalMetadataTest {

    private static final byte[] METADATA_XML = (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<metadata><groupId>com.example</groupId><artifactId>foo</artifactId>"
        + "<versioning><latest>1.0</latest><release>1.0</release>"
        + "<versions><version>1.0</version></versions></versioning></metadata>"
    ).getBytes(StandardCharsets.UTF_8);

    private static final String UPSTREAM_ETAG = "\"abc123\"";

    private static final String UPSTREAM_LAST_MODIFIED = "Wed, 01 Jan 2025 00:00:00 GMT";

    private Queue<ProxyArtifactEvent> events;

    @BeforeEach
    void init() {
        this.events = new LinkedList<>();
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
    }

    /**
     * Acceptance: A second metadata refresh after the soft TTL elapses sends
     * {@code If-None-Match} + {@code If-Modified-Since} headers; a 304
     * response leaves the cached blob byte-for-byte identical.
     */
    @Test
    void secondRefreshSendsValidatorsAndPreservesBlobOn304() throws Exception {
        final List<Headers> capturedHeaders = new ArrayList<>();
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final com.auto1.pantera.http.Slice upstream = (line, headers, body) -> {
            capturedHeaders.add(copyHeaders(headers));
            final int call = upstreamCalls.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("ETag", UPSTREAM_ETAG)
                        .header("Last-Modified", UPSTREAM_LAST_MODIFIED)
                        .body(METADATA_XML)
                        .build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.NOT_MODIFIED)
                    .header("ETag", UPSTREAM_ETAG)
                    .build()
            );
        };
        // Very short soft TTL so the second call falls in the stale window
        // and triggers a synchronous (hard-TTL) refresh.
        final MetadataCache metadataCache = new MetadataCache(
            Duration.ofMillis(50), Duration.ofMillis(100)
        );
        final CachedProxySlice slice = newSlice(upstream, metadataCache);
        // First call — cold miss, populates cache.
        final Response first = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(first.status(), new IsEqual<>(RsStatus.OK));
        final byte[] firstBody = first.body().asBytes();
        // Wait past the hard TTL so the next request blocks on upstream.
        Thread.sleep(200);
        // Second call — should fire upstream with conditional headers and
        // receive 304; cache serves the original bytes.
        final Response second = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/maven-metadata.xml"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "Second metadata response is 200 (304 short-circuits to cached bytes)",
            second.status(), new IsEqual<>(RsStatus.OK)
        );
        final byte[] secondBody = second.body().asBytes();
        MatcherAssert.assertThat(
            "Second upstream call must send If-None-Match",
            capturedHeaders.get(1).values("If-None-Match"),
            new IsEqual<>(List.of(UPSTREAM_ETAG))
        );
        MatcherAssert.assertThat(
            "Second upstream call must send If-Modified-Since",
            capturedHeaders.get(1).values("If-Modified-Since"),
            new IsEqual<>(List.of(UPSTREAM_LAST_MODIFIED))
        );
        MatcherAssert.assertThat(
            "Cached blob bytes are byte-for-byte identical after a 304",
            java.util.Arrays.equals(firstBody, secondBody), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Exactly two upstream calls observed (cold miss + refresh)",
            upstreamCalls.get(), new IsEqual<>(2)
        );
    }

    /**
     * Acceptance: A 200 response with new bytes on refresh replaces the
     * cache.
     */
    @Test
    void modifiedResponseReplacesCachedBody() throws Exception {
        final byte[] updatedXml = "<updated/>".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final com.auto1.pantera.http.Slice upstream = (line, headers, body) -> {
            final int call = upstreamCalls.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok()
                        .header("ETag", UPSTREAM_ETAG)
                        .header("Last-Modified", UPSTREAM_LAST_MODIFIED)
                        .body(METADATA_XML)
                        .build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("ETag", "\"v2\"")
                    .header("Last-Modified", "Thu, 02 Jan 2025 00:00:00 GMT")
                    .body(updatedXml)
                    .build()
            );
        };
        final MetadataCache metadataCache = new MetadataCache(
            Duration.ofMillis(50), Duration.ofMillis(100)
        );
        final CachedProxySlice slice = newSlice(upstream, metadataCache);
        slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/maven-metadata.xml"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        Thread.sleep(200);
        final Response refreshed = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/maven-metadata.xml"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            "200 refresh response carries the new bytes",
            java.util.Arrays.equals(refreshed.body().asBytes(), updatedXml),
            new IsEqual<>(true)
        );
    }

    /**
     * Acceptance: A 404 response on refresh clears the cache; the response
     * surfaces as 404 to the client.
     */
    @Test
    void notFoundOnRefreshSurfacesAsNotFound() throws Exception {
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final com.auto1.pantera.http.Slice upstream = (line, headers, body) -> {
            final int call = upstreamCalls.incrementAndGet();
            if (call == 1) {
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(METADATA_XML).build()
                );
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            );
        };
        final MetadataCache metadataCache = new MetadataCache(
            Duration.ofMillis(50), Duration.ofMillis(100)
        );
        final CachedProxySlice slice = newSlice(upstream, metadataCache);
        slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/maven-metadata.xml"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        Thread.sleep(200);
        final Response refreshed = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/maven-metadata.xml"),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            refreshed.status(), new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    private CachedProxySlice newSlice(
        final com.auto1.pantera.http.Slice upstream,
        final MetadataCache metadataCache
    ) {
        return new CachedProxySlice(
            upstream,
            (cacheKey, supplier, control) -> CompletableFuture.completedFuture(Optional.empty()),
            Optional.of(this.events), "gradle_proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(),
            Optional.of(new InMemoryStorage()),
            ProxyCacheConfig.defaults(),
            metadataCache
        );
    }

    private static Headers copyHeaders(final Headers headers) {
        return headers.copy();
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(
                final String artifact, final String version
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
