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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code onCacheWrite} extension point on
 * {@link BaseCachedProxySlice}. Mirrors the contract pinned by
 * {@link ProxyCacheWriterHookTest} but exercises the actual production
 * cache-write path used by every proxy adapter (Phase 4 / Task 19a).
 *
 * <p>This suite pins:
 * <ul>
 *   <li>callback fires exactly once after a successful cache write
 *       (post-storage-save, pre-temp-file-delete);</li>
 *   <li>callback exceptions are caught and isolated — the cache write
 *       still succeeds and the bytes are retrievable from storage;</li>
 *   <li>callback is NOT invoked when the upstream returns a non-success
 *       (4xx / 5xx) — only the success branch fires the hook.</li>
 * </ul>
 *
 * @since 2.2.0
 */
final class BaseCachedProxySliceHookTest {

    /** Cache key used by every test in the suite. */
    private static final String ARTIFACT_PATH =
        "/com/example/foo/1.0/foo-1.0.jar";

    /** Matching storage key (no leading slash — matches {@code KeyFromPath}). */
    private static final Key ARTIFACT_KEY =
        new Key.From("com/example/foo/1.0/foo-1.0.jar");

    /** Representative primary body. */
    private static final byte[] PRIMARY_BYTES =
        "primary-bytes-for-base-hook-test".getBytes(StandardCharsets.UTF_8);

    @Test
    @Timeout(10)
    @DisplayName("invokesCallbackAfterSuccessfulCacheWrite — happy path fires once with full event")
    void invokesCallbackAfterSuccessfulCacheWrite() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicReference<CacheWriteEvent> captured = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final Consumer<CacheWriteEvent> hook = event -> {
            calls.incrementAndGet();
            captured.set(event);
        };
        final HookTestSlice slice = new HookTestSlice(
            immediateOkUpstream(PRIMARY_BYTES), storage, hook
        );

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat("upstream success returns 200", resp.status(), equalTo(RsStatus.OK));
        assertEquals(1, calls.get(), "callback fired exactly once");
        final CacheWriteEvent event = captured.get();
        assertNotNull(event, "event captured");
        assertEquals("test-repo", event.repoName(), "repo name on event");
        assertEquals(ARTIFACT_KEY.string(), event.urlPath(), "url path on event");
        assertEquals((long) PRIMARY_BYTES.length, event.sizeBytes(), "size matches body");
        assertThat("bytesOnDisk path supplied", event.bytesOnDisk(), notNullValue());
        assertThat("writtenAt supplied", event.writtenAt(), notNullValue());
        assertTrue(storage.exists(ARTIFACT_KEY).join(), "primary persisted in storage");
    }

    @Test
    @Timeout(10)
    @DisplayName("callbackThrowingDoesNotAffectCacheWrite — write still succeeds + bytes in storage")
    void callbackThrowingDoesNotAffectCacheWrite() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Consumer<CacheWriteEvent> alwaysThrows = event -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom from base-cached-slice callback");
        };
        final HookTestSlice slice = new HookTestSlice(
            immediateOkUpstream(PRIMARY_BYTES), storage, alwaysThrows
        );

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat("upstream success still returns 200", resp.status(), equalTo(RsStatus.OK));
        assertEquals(1, calls.get(), "callback was invoked exactly once");
        assertTrue(
            storage.exists(ARTIFACT_KEY).join(),
            "primary persisted despite callback throwing"
        );
        // Sanity check the bytes survived the round-trip.
        final byte[] persisted = storage.value(ARTIFACT_KEY).join().asBytes();
        assertEquals(PRIMARY_BYTES.length, persisted.length, "persisted bytes length");
    }

    @Test
    @Timeout(10)
    @DisplayName("callbackNotInvokedOnFailure — upstream 5xx does NOT fire the hook")
    void callbackNotInvokedOnFailureUpstream5xx() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Consumer<CacheWriteEvent> hook = event -> calls.incrementAndGet();
        final Slice failingUpstream = (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.unavailable()
                    .textBody("upstream is sad")
                    .build()
            );
        final HookTestSlice slice = new HookTestSlice(
            failingUpstream, storage, hook
        );

        slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertEquals(0, calls.get(), "hook NOT invoked on upstream failure");
        assertThat(
            "no bytes persisted on failure",
            storage.exists(ARTIFACT_KEY).join(),
            equalTo(false)
        );
    }

    @Test
    @Timeout(10)
    @DisplayName("callbackNotInvokedOnFailure — upstream 404 does NOT fire the hook")
    void callbackNotInvokedOnFailureUpstream404() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicInteger calls = new AtomicInteger();
        final Consumer<CacheWriteEvent> hook = event -> calls.incrementAndGet();
        final Slice notFoundUpstream = (line, headers, body) ->
            CompletableFuture.completedFuture(
                ResponseBuilder.notFound()
                    .textBody("nope")
                    .build()
            );
        final HookTestSlice slice = new HookTestSlice(
            notFoundUpstream, storage, hook
        );

        slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertEquals(0, calls.get(), "hook NOT invoked on upstream 404");
        assertThat(
            "no bytes persisted on 404",
            storage.exists(ARTIFACT_KEY).join(),
            equalTo(false)
        );
    }

    @Test
    @Timeout(10)
    @DisplayName("default constructor (no callback) is a no-op — backwards-compatible")
    void defaultConstructorIsNoOp() throws Exception {
        final Storage storage = new InMemoryStorage();
        // Constructor without callback must still compile + behave correctly.
        final NoCallbackTestSlice slice = new NoCallbackTestSlice(
            immediateOkUpstream(PRIMARY_BYTES), storage
        );

        final Response resp = slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        assertThat("response is 200", resp.status(), equalTo(RsStatus.OK));
        assertTrue(storage.exists(ARTIFACT_KEY).join(), "primary persisted");
    }

    @Test
    @Timeout(10)
    @DisplayName("size on event reflects actual body size (>0)")
    void eventSizeIsPositive() throws Exception {
        final Storage storage = new InMemoryStorage();
        final AtomicReference<CacheWriteEvent> captured = new AtomicReference<>();
        final HookTestSlice slice = new HookTestSlice(
            immediateOkUpstream(PRIMARY_BYTES), storage, captured::set
        );

        slice.response(
            new RequestLine(RqMethod.GET, ARTIFACT_PATH),
            Headers.EMPTY,
            Content.EMPTY
        ).get(5, TimeUnit.SECONDS);

        final CacheWriteEvent event = captured.get();
        assertThat("event captured", event, notNullValue());
        assertThat("size > 0", event.sizeBytes(), greaterThan(0L));
        // Sanity: bytesOnDisk path should have been alive at the moment of fire.
        assertThat("bytesOnDisk supplied", event.bytesOnDisk(), notNullValue());
    }

    // ===== helpers =====

    /**
     * Build an upstream slice that answers a 200 with {@code body} immediately.
     */
    private static Slice immediateOkUpstream(final byte[] body) {
        return (line, headers, content) -> CompletableFuture.completedFuture(
            ResponseBuilder.ok()
                .header("Content-Type", "application/java-archive")
                .body(body)
                .build()
        );
    }

    /**
     * Test subclass that exercises the new constructor accepting an
     * {@code onCacheWrite} callback. All paths cacheable and storage-backed
     * so requests flow through {@code fetchAndCache → cacheResponse}.
     */
    private static final class HookTestSlice extends BaseCachedProxySlice {

        HookTestSlice(
            final Slice upstream, final Storage storage,
            final Consumer<CacheWriteEvent> onCacheWrite
        ) {
            super(
                upstream,
                new FromStorageCache(storage),
                "test-repo",
                "test",
                "http://upstream",
                Optional.of(storage),
                Optional.empty(),
                ProxyCacheConfig.defaults(),
                null,
                null,
                onCacheWrite
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            return true;
        }
    }

    /**
     * Test subclass that uses the existing (no-callback) constructor. Verifies
     * the no-op default path still works — backwards compatibility for existing
     * adapters and tests that don't supply a callback.
     */
    private static final class NoCallbackTestSlice extends BaseCachedProxySlice {

        NoCallbackTestSlice(final Slice upstream, final Storage storage) {
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
