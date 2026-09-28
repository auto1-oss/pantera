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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Regression test for the 2.2.6 silent 404→"not modified" conflation.
 *
 * <p>Before 2.2.7, {@code HttpNpmRemote.loadPackageConditional} collapsed an
 * upstream 404 into the same empty signal as a genuine 304, and
 * {@code NpmProxy.conditionalRefresh} answered it by bumping
 * {@code lastRefreshed} — silently re-arming the metadata TTL on stale
 * content, with no log trace and no retry until the next full TTL window.
 * This test scripts an upstream that 200s once and then 404s, and proves the
 * refresh timestamp stays stale so every subsequent request keeps retrying
 * the refresh (behavioural proof: a third upstream call happens; with the
 * old timestamp-bump behaviour the third call never fires).</p>
 *
 * @since 2.2.7
 */
final class NpmProxyConditionalRefresh404Test {

    private static final String NAME = "asdas";

    private static final String ETAG = "\"v1-etag\"";

    @Test
    @Timeout(15)
    void upstream404DoesNotBumpRefreshTimestamp() throws Exception {
        final Vanishing origin = new Vanishing();
        final NpmRemote remote = new HttpNpmRemote(origin);
        final Storage delegate = new InMemoryStorage();
        final NpmProxyStorage storage = new RxNpmProxyStorage(new RxStorageWrapper(delegate));
        final NpmProxy npm = new NpmProxy(storage, remote, Duration.ofHours(1));

        // Cold fetch (200): packument + ETag land in storage.
        npm.getPackageMetadataOnly(NAME).blockingGet();
        MatcherAssert.assertThat(
            "cold fetch makes exactly one upstream call",
            origin.calls(), new IsEqual<>(1)
        );
        final NpmPackage.Metadata persisted = storage.getPackageMetadata(NAME).blockingGet();
        final byte[] before = storage.getPackageContent(NAME)
            .blockingGet().asBytesFuture().get(5, TimeUnit.SECONDS);

        // Force staleness deterministically — an old lastRefreshed, never a sleep.
        final OffsetDateTime staleStamp = OffsetDateTime.now().minus(2, ChronoUnit.HOURS);
        storage.saveMetadataOnly(
            NAME,
            new NpmPackage.Metadata(
                persisted.lastModified(),
                staleStamp,
                persisted.contentHash().orElse(null),
                persisted.abbreviatedHash().orElse(null),
                persisted.upstreamEtag().orElse(null)
            )
        ).blockingAwait();

        // Serve #1: stale-while-revalidate fires a conditional refresh that
        // now meets a 404.
        npm.getPackageMetadataOnly(NAME).blockingGet();
        awaitCalls(origin, 2);

        // The behavioural proof that the timestamp was NOT bumped: the next
        // serve must consider the metadata still stale and retry the
        // refresh. With the pre-2.2.7 bump, this third call never happens.
        awaitRetry(npm, origin, 3);

        final NpmPackage.Metadata after = storage.getPackageMetadata(NAME).blockingGet();
        MatcherAssert.assertThat(
            "an upstream 404 must not advance lastRefreshed past the stale stamp",
            after.lastRefreshed().isBefore(OffsetDateTime.now().minus(1, ChronoUnit.HOURS)),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the cached packument bytes must be kept (fail-open), not cleared",
            storage.getPackageContent(NAME).blockingGet()
                .asBytesFuture().get(5, TimeUnit.SECONDS),
            new IsEqual<>(before)
        );
    }

    /**
     * Poll until the origin recorded at least {@code expected} calls.
     */
    private static void awaitCalls(final Vanishing origin, final int expected) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (origin.calls() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                    "background refresh never landed (calls=" + origin.calls() + ")"
                );
            }
            Thread.sleep(5);
        }
    }

    /**
     * Re-serve until the retry refresh reaches the origin. Serving in a loop
     * (rather than once) keeps the test deterministic: the in-flight guard
     * may still hold the package for a moment while the previous failed
     * refresh finishes tearing down.
     */
    private static void awaitRetry(
        final NpmProxy npm, final Vanishing origin, final int expected
    ) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (origin.calls() < expected) {
            npm.getPackageMetadataOnly(NAME).blockingGet();
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                    "404 refresh was not retried — lastRefreshed was bumped? (calls="
                        + origin.calls() + ")"
                );
            }
            Thread.sleep(5);
        }
    }

    /**
     * Scripted upstream: 200 with ETag on the first call, bodiless 404 on
     * every later call — a package that vanished (or an upstream whose URL
     * broke) after being cached.
     */
    private static final class Vanishing implements Slice {

        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return this.calls.get();
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line, final Headers headers, final Content body
        ) {
            final int call = this.calls.incrementAndGet();
            if (call == 1) {
                return ResponseBuilder.ok()
                    .header("Last-Modified", "Tue, 24 Mar 2020 12:15:16 GMT")
                    .header("ETag", ETAG)
                    .body(new TestResource("json/original.json").asBytes())
                    .completedFuture();
            }
            return ResponseBuilder.notFound().completedFuture();
        }
    }
}
