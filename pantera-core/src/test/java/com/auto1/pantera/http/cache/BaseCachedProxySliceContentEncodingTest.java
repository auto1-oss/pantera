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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression tests: {@link BaseCachedProxySlice} must not propagate {@code Content-Encoding}
 * headers from upstream to clients after Jetty auto-decodes compressed bodies.
 *
 * <p>Root cause: Jetty's {@code GZIPContentDecoder} decodes gzip response bodies but
 * leaves {@code Content-Encoding: gzip} in the upstream response headers. When
 * {@code BaseCachedProxySlice} passes those headers directly to the caller (via
 * {@code fetchDirect}, {@code handleRootPath} or from the metadata cache hit path),
 * clients receive plain bytes paired with a gzip header and fail with
 * {@code Z_DATA_ERROR: zlib: incorrect header check}.
 *
 * @since 1.20.13
 */
final class BaseCachedProxySliceContentEncodingTest {

    // ===== Unit tests for the stripContentEncoding() helper =====

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "GZIP", "deflate", "br", "x-gzip"})
    void stripContentEncodingRemovesKnownEncodings(final String encoding) {
        final Headers headers = new Headers(List.of(
            new Header("Content-Encoding", encoding),
            new Header("Content-Type", "application/octet-stream"),
            new Header("Content-Length", "1234")
        ));
        final Headers result = BaseCachedProxySlice.stripContentEncoding(headers);
        assertFalse(
            hasHeader(result, "Content-Encoding"),
            "Content-Encoding must be stripped for encoding: " + encoding
        );
        assertFalse(
            hasHeader(result, "Content-Length"),
            "Content-Length must be stripped when encoding was decoded"
        );
        assertEquals(
            "application/octet-stream",
            firstHeader(result, "Content-Type"),
            "Content-Type must be preserved"
        );
    }

    @Test
    void stripContentEncodingIsNoopWhenAbsent() {
        final Headers headers = new Headers(List.of(
            new Header("Content-Type", "text/plain"),
            new Header("Content-Length", "42"),
            new Header("ETag", "\"deadbeef\"")
        ));
        final Headers result = BaseCachedProxySlice.stripContentEncoding(headers);
        assertEquals(
            "text/plain",
            firstHeader(result, "Content-Type")
        );
        assertEquals(
            "42",
            firstHeader(result, "Content-Length"),
            "Content-Length preserved when no transfer encoding"
        );
        assertEquals(
            "\"deadbeef\"",
            firstHeader(result, "ETag")
        );
    }

    @Test
    void stripContentEncodingIsNoopForIdentityEncoding() {
        final Headers headers = new Headers(List.of(
            new Header("Content-Encoding", "identity"),
            new Header("Content-Type", "text/plain"),
            new Header("Content-Length", "10")
        ));
        final Headers result = BaseCachedProxySlice.stripContentEncoding(headers);
        // "identity" is not decoded by Jetty, so nothing should be stripped
        assertEquals(
            "identity",
            firstHeader(result, "Content-Encoding"),
            "identity encoding must not be stripped"
        );
        assertEquals(
            "10",
            firstHeader(result, "Content-Length"),
            "Content-Length preserved for identity encoding"
        );
    }

    // ===== Integration tests via a minimal concrete subclass =====

    @Test
    void fetchDirectDoesNotPropagateContentEncodingGzip() {
        final byte[] decodedBody = "plain bytes after gzip decode".getBytes();
        final MinimalProxySlice slice = new MinimalProxySlice(
            // Upstream returns decoded bytes but STILL has Content-Encoding: gzip in headers
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Encoding", "gzip")
                    .header("Content-Type", "application/java-archive")
                    .header("Content-Length", "100")
                    .body(decodedBody)
                    .build()
            ),
            false  // non-cacheable → always goes through fetchDirect
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, response.status());
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "fetchDirect must strip Content-Encoding: gzip"
        );
        assertEquals(
            "application/java-archive",
            firstHeader(response.headers(), "Content-Type"),
            "Content-Type must be preserved"
        );
        // Note: Content-Length may be present with the correct (decoded) body size —
        // we only require that the stale compressed Content-Length is gone.
        // If Content-Length is present it must match the actual decoded body.
        final String contentLength = firstHeader(response.headers(), "Content-Length");
        if (contentLength != null) {
            assertEquals(
                String.valueOf(decodedBody.length),
                contentLength,
                "If Content-Length is set it must reflect the decoded body size, not compressed size"
            );
        }
    }

    @Test
    void handleRootPathDoesNotPropagateContentEncodingGzip() {
        final byte[] decodedBody = "root response".getBytes();
        final MinimalProxySlice slice = new MinimalProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Encoding", "gzip")
                    .header("Content-Type", "application/json")
                    .body(decodedBody)
                    .build()
            ),
            true
        );
        // "/" triggers handleRootPath()
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, response.status());
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "handleRootPath must strip Content-Encoding: gzip"
        );
    }

    @Test
    void cacheResponseDoesNotStoreContentEncodingGzip() {
        final InMemoryStorage storage = new InMemoryStorage();
        final CachedArtifactMetadataStore store = new CachedArtifactMetadataStore(storage);
        final byte[] decodedBody = "cached content".getBytes();

        final MinimalProxySlice slice = new MinimalProxySlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Encoding", "gzip")
                    .header("Content-Type", "application/java-archive")
                    .header("ETag", "\"v1\"")
                    .header("Content-Length", "500")
                    .body(decodedBody)
                    .build()
            ),
            true,  // cacheable
            storage
        );
        // First request: fetches and caches
        slice.response(
            new RequestLine(RqMethod.GET, "/com/example/lib/1.0/lib-1.0.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        // Load the stored metadata and verify Content-Encoding was NOT persisted
        final Key key = new Key.From("com/example/lib/1.0/lib-1.0.jar");
        final Optional<CachedArtifactMetadataStore.Metadata> meta = store.load(key).join();
        if (meta.isPresent()) {
            assertFalse(
                hasHeader(meta.get().headers(), "Content-Encoding"),
                "Metadata store must NOT contain Content-Encoding: gzip"
            );
        }
        // Second request: serves from cache — must not have Content-Encoding: gzip
        final Response cached = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/lib/1.0/lib-1.0.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, cached.status());
        assertFalse(
            hasHeader(cached.headers(), "Content-Encoding"),
            "Cache hit response must not have Content-Encoding: gzip"
        );
    }

    @Test
    void cacheHitPathStripsContentEncodingFromExistingMetadata() throws Exception {
        // Simulate metadata that was stored BEFORE the fix (poisoned with Content-Encoding: gzip)
        final InMemoryStorage storage = new InMemoryStorage();
        final CachedArtifactMetadataStore store = new CachedArtifactMetadataStore(storage);
        final Key key = new Key.From("com/example/old/1.0/old-1.0.jar");
        final byte[] decodedBody = "old cached content".getBytes();
        // Manually save poisoned metadata (with Content-Encoding: gzip)
        final Headers poisonedHeaders = new Headers(List.of(
            new Header("Content-Encoding", "gzip"),
            new Header("Content-Type", "application/java-archive"),
            new Header("Content-Length", "999")
        ));
        store.save(
            key,
            poisonedHeaders,
            new CachedArtifactMetadataStore.ComputedDigests(decodedBody.length, java.util.Map.of())
        ).join();
        // Save content to storage too
        storage.save(key, new Content.From(decodedBody)).join();

        final MinimalProxySlice slice = new MinimalProxySlice(
            // Upstream should not be called for a cache hit
            (line, headers, body) -> CompletableFuture.failedFuture(
                new AssertionError("Upstream must not be called on cache hit")
            ),
            true,
            storage,
            (cacheKey, supplier, control) ->
                CompletableFuture.completedFuture(Optional.of(new Content.From(decodedBody)))
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/old/1.0/old-1.0.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        assertEquals(RsStatus.OK, response.status());
        assertFalse(
            hasHeader(response.headers(), "Content-Encoding"),
            "Cache hit must strip Content-Encoding: gzip from previously-poisoned metadata"
        );
    }

    // ===== Helpers =====

    private static boolean hasHeader(final Headers headers, final String name) {
        return StreamSupport.stream(headers.spliterator(), false)
            .anyMatch(h -> name.equalsIgnoreCase(h.getKey()));
    }

    private static String firstHeader(final Headers headers, final String name) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> name.equalsIgnoreCase(h.getKey()))
            .map(Header::getValue)
            .findFirst()
            .orElse(null);
    }

    /**
     * Minimal concrete subclass of {@link BaseCachedProxySlice} for tests.
     * Delegates all abstract/hook methods to simple defaults.
     */
    private static final class MinimalProxySlice extends BaseCachedProxySlice {

        /**
         * Whether paths are cacheable.
         */
        private final boolean cacheable;

        /**
         * Ctor for non-storage-backed tests (fetchDirect / handleRootPath).
         * @param upstream Upstream slice
         * @param cacheable Whether isCacheable returns true
         */
        MinimalProxySlice(
            final com.auto1.pantera.http.Slice upstream,
            final boolean cacheable
        ) {
            super(
                upstream,
                Cache.NOP,
                "test-repo",
                "test",
                "http://upstream",
                Optional.empty(),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
            this.cacheable = cacheable;
        }

        /**
         * Ctor for storage-backed tests (cacheResponse).
         * @param upstream Upstream slice
         * @param cacheable Whether isCacheable returns true
         * @param storage Backing storage
         */
        MinimalProxySlice(
            final com.auto1.pantera.http.Slice upstream,
            final boolean cacheable,
            final com.auto1.pantera.asto.Storage storage
        ) {
            super(
                upstream,
                new com.auto1.pantera.asto.cache.FromStorageCache(storage),
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
            this.cacheable = cacheable;
        }

        /**
         * Ctor for cache-hit tests with a custom Cache implementation.
         * @param upstream Upstream slice
         * @param cacheable Whether isCacheable returns true
         * @param storage Backing storage
         * @param cache Custom cache (to inject pre-stored content)
         */
        MinimalProxySlice(
            final com.auto1.pantera.http.Slice upstream,
            final boolean cacheable,
            final com.auto1.pantera.asto.Storage storage,
            final Cache cache
        ) {
            super(
                upstream,
                cache,
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults()
            );
            this.cacheable = cacheable;
        }

        @Override
        protected boolean isCacheable(final String path) {
            return this.cacheable;
        }
    }
}
