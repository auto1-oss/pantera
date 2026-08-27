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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WS6.1 — proves {@link NpmProxy#getPackageMetadataOnly(String)}'s
 * background stale-while-revalidate refresh actually issues a conditional
 * ({@code If-None-Match}) request once the upstream ETag round-trips
 * through {@link RxNpmProxyStorage#save(NpmPackage)}, and that a clean
 * {@code 304} skips the full re-download / re-parse cycle entirely.
 *
 * <p>Before the WS6.1 fix, {@code RxNpmProxyStorage.save}'s enriched
 * {@code Metadata} ctor call omitted {@code pkg.meta().upstreamEtag()},
 * so {@code metadata.upstreamEtag()} was always empty on every path that
 * goes through {@code save(NpmPackage)} — {@code NpmProxy.conditionalRefresh}
 * (gated on {@code metadata.upstreamEtag().isPresent()}) therefore always
 * fell through to a full unconditional refetch, making the entire
 * conditional-request code path in {@link HttpNpmRemote#loadPackageConditional}
 * permanently dead.</p>
 *
 * <p>Uses a real {@link HttpNpmRemote} (not a mock) because
 * {@code NpmProxy.conditionalRefresh} explicitly branches on
 * {@code this.remote instanceof HttpNpmRemote} — a plain
 * {@code NpmRemote} mock can never exercise that branch.</p>
 *
 * @since 2.3.0
 */
final class NpmProxyConditionalRefreshTest {

    private static final String NAME = "asdas";

    private static final String LAST_MODIFIED = "Tue, 24 Mar 2020 12:15:16 GMT";

    private static final String ETAG = "\"v1-etag\"";

    @Test
    @Timeout(10)
    void conditionalRefreshOn304SkipsFullReDownloadAndReParse() throws Exception {
        final ScriptedOrigin origin = new ScriptedOrigin();
        final NpmRemote remote = new HttpNpmRemote(origin);
        final Storage delegate = new InMemoryStorage();
        final NpmProxyStorage storage = new RxNpmProxyStorage(new RxStorageWrapper(delegate));
        final NpmProxy npm = new NpmProxy(storage, remote, Duration.ofHours(1));

        // Cold fetch: one unconditional upstream call.
        npm.getPackageMetadataOnly(NAME).blockingGet();
        MatcherAssert.assertThat(
            "cold fetch makes exactly one (unconditional) upstream call",
            origin.calls(), new IsEqual<>(1)
        );

        // Read back what RxNpmProxyStorage.save() ACTUALLY persisted — the
        // real WS6.1 proof point. Before the fix, the enriched Metadata
        // ctor call inside save() omitted pkg.meta().upstreamEtag(), so
        // this would read back empty regardless of what HttpNpmRemote
        // extracted from the response.
        final NpmPackage.Metadata persisted = storage.getPackageMetadata(NAME).blockingGet();
        MatcherAssert.assertThat(
            "the upstream ETag must round-trip through RxNpmProxyStorage.save()",
            persisted.upstreamEtag(), new IsEqual<>(Optional.of(ETAG))
        );
        final byte[] packumentAfterColdFetch = storage.getPackageContent(NAME)
            .blockingGet().asBytesFuture().get(5, TimeUnit.SECONDS);

        // Force staleness (TTL is 1h) while preserving exactly what was
        // persisted (including the ETag) — mirrors the deterministic
        // staleness setup NpmProxyTest.MetadataTtlExceeded already uses
        // (an old lastRefreshed timestamp), never a real sleep.
        storage.saveMetadataOnly(
            NAME,
            new NpmPackage.Metadata(
                persisted.lastModified(),
                OffsetDateTime.now().minus(2, ChronoUnit.HOURS),
                persisted.contentHash().orElse(null),
                persisted.abbreviatedHash().orElse(null),
                persisted.upstreamEtag().orElse(null)
            )
        ).blockingAwait();

        // Second call sees stale cached metadata: returns it immediately
        // (stale-while-revalidate) and fires a background conditionalRefresh.
        npm.getPackageMetadataOnly(NAME).blockingGet();

        // Poll for the background refresh's conditional call to land —
        // never a wall-clock sleep, per the project's testing doctrine.
        awaitCalls(origin, 2);

        MatcherAssert.assertThat(
            "conditional refresh must issue exactly one more (304) request — no retry storm",
            origin.calls(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the conditional request must carry If-None-Match with the captured ETag",
            origin.lastIfNoneMatch(), new IsEqual<>(Optional.of(ETAG))
        );
        MatcherAssert.assertThat(
            "a clean 304 must not re-transfer the packument body",
            origin.lastResponseBodyBytes(), new IsEqual<>(0)
        );
        final byte[] packumentAfter304 = storage.getPackageContent(NAME)
            .blockingGet().asBytesFuture().get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a 304 must not rewrite the cached packument bytes — no re-parse occurred",
            packumentAfter304, new IsEqual<>(packumentAfterColdFetch)
        );
    }

    /**
     * Poll until {@code origin} has recorded at least {@code expected}
     * calls, or fail after a bounded deadline. The background refresh
     * runs on a separate scheduler (see {@code NpmProxy.backgroundRefresh}),
     * so its landing is polled for rather than assumed instantaneous.
     */
    private static void awaitCalls(final ScriptedOrigin origin, final int expected) throws Exception {
        final long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (origin.calls() < expected) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError(
                    "Background conditional refresh never landed (calls=" + origin.calls() + ")"
                );
            }
            Thread.sleep(5);
        }
    }

    /**
     * Scripted upstream {@link Slice}: unconditionally serves
     * {@code json/original.json} with {@code Last-Modified} + {@code ETag}
     * on the first call; on any subsequent call carrying a matching
     * {@code If-None-Match}, returns a bodiless {@code 304}.
     */
    private static final class ScriptedOrigin implements Slice {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();
        private final AtomicInteger lastResponseBodyBytes = new AtomicInteger();

        int calls() {
            return this.calls.get();
        }

        Optional<String> lastIfNoneMatch() {
            return Optional.ofNullable(this.lastIfNoneMatch.get());
        }

        int lastResponseBodyBytes() {
            return this.lastResponseBodyBytes.get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            // Record everything BEFORE publishing the call counter: awaitCalls()
            // polls on calls(), so incrementing first lets the asserting thread
            // observe calls==2 while lastIfNoneMatch/lastResponseBodyBytes still
            // hold call #1's values — a race that shows up as a bogus
            // "conditional request carried no If-None-Match" failure under load.
            // The counter is the readiness signal, so it must be set last.
            final java.util.List<String> inm = headers.values("If-None-Match");
            final String seen = inm.isEmpty() ? null : inm.get(0);
            this.lastIfNoneMatch.set(seen);
            if (ETAG.equals(seen)) {
                this.lastResponseBodyBytes.set(0);
                this.calls.incrementAndGet();
                return ResponseBuilder.from(RsStatus.NOT_MODIFIED).completedFuture();
            }
            final byte[] content = new TestResource("json/original.json").asBytes();
            this.lastResponseBodyBytes.set(content.length);
            this.calls.incrementAndGet();
            return ResponseBuilder.ok()
                .header("Last-Modified", LAST_MODIFIED)
                .header("ETag", ETAG)
                .body(content)
                .completedFuture();
        }
    }
}
