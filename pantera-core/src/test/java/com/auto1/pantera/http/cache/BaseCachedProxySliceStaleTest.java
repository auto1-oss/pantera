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

import com.amihaiemil.eoyaml.Yaml;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests stale-while-revalidate for binary artifact GET requests in
 * {@link BaseCachedProxySlice}.
 *
 * <p>The SWR scenario: the Cache layer reports a miss (e.g. TTL expired or eviction),
 * so the proxy goes to upstream. Upstream fails. But the artifact bytes still exist in
 * the backing Storage from a previous fetch. With SWR enabled, the proxy serves those
 * stale bytes with 200 + {@code X-Pantera-Stale: true} instead of propagating the error.
 *
 * @since 2.1.3
 */
@SuppressWarnings("PMD.TooManyMethods")
final class BaseCachedProxySliceStaleTest {

    private static final String ARTIFACT_PATH =
        "/com/example/foo/1.0/foo-1.0.jar";

    private static final byte[] CACHED_BYTES =
        "cached artifact bytes".getBytes();

    /**
     * Build a ProxyCacheConfig with stale-while-revalidate enabled.
     */
    private static ProxyCacheConfig swrEnabled() {
        return new ProxyCacheConfig(
            Yaml.createYamlMappingBuilder()
                .add("cache",
                    Yaml.createYamlMappingBuilder()
                        .add("stale_while_revalidate",
                            Yaml.createYamlMappingBuilder()
                                .add("enabled", "true")
                                .build())
                        .build())
                .build()
        );
    }

    /**
     * A Cache implementation that always reports a miss, forcing upstream fetches.
     * This simulates a TTL-expired or evicted cache entry, while the backing storage
     * still retains the artifact bytes from a previous fetch.
     */
    private static final class AlwaysMissCache implements Cache {
        @Override
        public CompletionStage<Optional<? extends Content>> load(
            final Key key, final Remote remote, final CacheControl control
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    // ===== Test: upstream exception + cached bytes + SWR enabled → serve stale 200 =====

    @Test
    @DisplayName("Upstream timeout + cached bytes + SWR enabled → serve stale 200")
    void serveStaleBytesOnUpstreamTimeout() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        storage.save(key, new Content.From(CACHED_BYTES)).join();

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new SocketTimeoutException("upstream timed out")
            ),
            storage,
            new AlwaysMissCache(),
            swrEnabled()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, response.status(),
            "Stale serve must return 200");
        assertTrue(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale header must be set on stale serve"
        );
        assertEquals(
            "true",
            firstHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must be 'true'"
        );
    }

    // ===== Test: upstream 5xx + cached bytes + SWR enabled → serve stale 200 =====

    @Test
    @DisplayName("Upstream 5xx + cached bytes + SWR enabled → serve stale 200")
    void serveStaleBytesOnUpstream500() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        storage.save(key, new Content.From(CACHED_BYTES)).join();

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.from(RsStatus.INTERNAL_ERROR)
                    .textBody("internal server error")
                    .build()
            ),
            storage,
            new AlwaysMissCache(),
            swrEnabled()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, response.status(),
            "Stale serve must return 200 on upstream 5xx");
        assertTrue(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale header must be set on stale serve"
        );
    }

    // ===== Test: upstream exception + NO cached bytes + SWR enabled → propagate error =====

    @Test
    @DisplayName("Upstream error + no cached bytes + SWR enabled → propagate error (non-200)")
    void propagateErrorWhenNoCachedBytes() {
        final InMemoryStorage storage = new InMemoryStorage();
        // No bytes stored — storage is empty

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new SocketTimeoutException("upstream timed out")
            ),
            storage,
            new AlwaysMissCache(),
            swrEnabled()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertFalse(
            RsStatus.OK.equals(response.status()),
            "Must NOT serve 200 when there are no cached bytes"
        );
        assertFalse(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must NOT be set when nothing to serve"
        );
    }

    // ===== Test: upstream exception + cached bytes + SWR DISABLED → propagate error =====

    @Test
    @DisplayName("Upstream error + cached bytes + SWR disabled → propagate error (non-200)")
    void disabledConfigPropagatesError() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        storage.save(key, new Content.From(CACHED_BYTES)).join();

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new SocketTimeoutException("upstream timed out")
            ),
            storage,
            new AlwaysMissCache(),
            ProxyCacheConfig.defaults()  // staleWhileRevalidateEnabled() = false by default
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertFalse(
            RsStatus.OK.equals(response.status()),
            "Must NOT serve stale 200 when SWR is disabled"
        );
        assertFalse(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must NOT be set when SWR is disabled"
        );
    }

    // ===== Test: upstream 404 + cached bytes + SWR enabled → serve 404 (not stale) =====

    @Test
    @DisplayName("Upstream 404 + cached bytes + SWR enabled → serve 404 (not stale)")
    void upstreamNotFoundIsNotServedStale() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        storage.save(key, new Content.From(CACHED_BYTES)).join();

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.completedFuture(
                ResponseBuilder.notFound().build()
            ),
            storage,
            new AlwaysMissCache(),
            swrEnabled()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.NOT_FOUND, response.status(),
            "404 from upstream must propagate as 404, not stale 200");
        assertFalse(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must NOT be set for a 404"
        );
    }

    // ===== Tests: staleMaxAge enforcement =====

    @Test
    @DisplayName("Stale beyond staleMaxAge → refuse to serve, propagate error")
    void rejectStaleBeyondMaxAge() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        storage.save(key, new Content.From(CACHED_BYTES)).join();
        // Write metadata with savedAt 2 hours ago, staleMaxAge is 10 minutes
        final Instant twoHoursAgo = Instant.now().minus(Duration.ofHours(2));
        saveMetadataWithSavedAt(storage, key, twoHoursAgo);

        final ProxyCacheConfig config = new ProxyCacheConfig(
            Yaml.createYamlMappingBuilder()
                .add("cache",
                    Yaml.createYamlMappingBuilder()
                        .add("stale_while_revalidate",
                            Yaml.createYamlMappingBuilder()
                                .add("enabled", "true")
                                .add("max_age", "PT10M")
                                .build())
                        .build())
                .build()
        );

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new SocketTimeoutException("upstream timed out")
            ),
            storage,
            new AlwaysMissCache(),
            config
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertFalse(
            RsStatus.OK.equals(response.status()),
            "Must NOT serve stale 200 when artifact age exceeds staleMaxAge"
        );
        assertFalse(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must NOT be set when staleMaxAge exceeded"
        );
    }

    @Test
    @DisplayName("Stale within staleMaxAge → serve stale 200 with Age header")
    void serveStaleWithinMaxAge() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        storage.save(key, new Content.From(CACHED_BYTES)).join();
        // Write metadata with savedAt 5 minutes ago, staleMaxAge is 1 hour
        final Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(5));
        saveMetadataWithSavedAt(storage, key, fiveMinutesAgo);

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new SocketTimeoutException("upstream timed out")
            ),
            storage,
            new AlwaysMissCache(),
            swrEnabled()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, response.status(),
            "Must serve stale 200 when artifact age is within staleMaxAge");
        assertTrue(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must be set on stale serve"
        );
        assertTrue(
            hasHeader(response.headers(), "Age"),
            "Age header (RFC 7234) must be set on stale serve"
        );
        final String ageValue = firstHeader(response.headers(), "Age");
        assertNotNull(ageValue, "Age header value must not be null");
        assertTrue(
            Long.parseLong(ageValue) >= 270 && Long.parseLong(ageValue) <= 360,
            "Age must be approximately 5 minutes (300s) ± tolerance, got: " + ageValue
        );
    }

    @Test
    @DisplayName("No metadata sidecar → fall back to existence check (legacy behavior)")
    void noMetadataFallsBackToExistence() {
        final InMemoryStorage storage = new InMemoryStorage();
        final Key key = new Key.From("com/example/foo/1.0/foo-1.0.jar");
        // Save artifact bytes only — no metadata sidecar
        storage.save(key, new Content.From(CACHED_BYTES)).join();

        final StaleTestSlice slice = new StaleTestSlice(
            (line, headers, body) -> CompletableFuture.failedFuture(
                new SocketTimeoutException("upstream timed out")
            ),
            storage,
            new AlwaysMissCache(),
            swrEnabled()
        );

        final Response response = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).join();

        assertEquals(RsStatus.OK, response.status(),
            "Must serve stale 200 via existence fallback when no metadata sidecar present");
        assertTrue(
            hasHeader(response.headers(), "X-Pantera-Stale"),
            "X-Pantera-Stale must be set on legacy stale serve"
        );
    }

    // ===== Helpers =====

    /**
     * Write a minimal metadata JSON sidecar with an explicit savedAt timestamp directly to
     * storage (bypasses the normal save() which always uses Instant.now()).
     */
    private static void saveMetadataWithSavedAt(
        final InMemoryStorage storage,
        final Key artifactKey,
        final Instant savedAt
    ) {
        final String json = String.format(
            "{\"size\":21,\"headers\":[],\"digests\":{},\"savedAt\":\"%s\"}",
            savedAt.toString()
        );
        final Key metaKey = new Key.From(artifactKey.string() + ".pantera-meta.json");
        storage.save(metaKey, new Content.From(json.getBytes(StandardCharsets.UTF_8))).join();
    }

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
     * Concrete subclass of {@link BaseCachedProxySlice} for stale-while-revalidate tests.
     * All paths are cacheable; uses storage-backed mode with a custom cache.
     */
    private static final class StaleTestSlice extends BaseCachedProxySlice {

        StaleTestSlice(
            final com.auto1.pantera.http.Slice upstream,
            final com.auto1.pantera.asto.Storage storage,
            final Cache cache,
            final ProxyCacheConfig config
        ) {
            super(
                upstream,
                cache,
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                config
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }
}
