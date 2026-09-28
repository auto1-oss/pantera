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

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;

/**
 * WS5.2 regression coverage: the {@code packumentWriteHook} — production
 * wiring's extension point for invalidating the cooldown {@code
 * FilteredMetadataCache} envelope — must fire on EVERY genuine packument
 * content change, including the ETag-conditional stale-while-revalidate
 * refresh path in {@link NpmProxy#conditionalRefresh}.
 *
 * <p>Before this fix, {@code conditionalRefresh}'s "upstream ETag differs"
 * branch called {@code storage.save(pkg)} directly, without the {@code
 * .doOnComplete(() -> firePackumentWriteHook(...))} that {@code
 * remotePackageAndSave} already had — so the single most common
 * background-refresh outcome (a warm, previously-cached package whose
 * upstream content changed) silently skipped the hook. A version
 * published upstream during a background refresh stayed invisible behind
 * the {@code FilteredMetadataCache} envelope's own TTL on top of the
 * packument TTL.
 *
 * @since 2.3.0
 */
final class NpmProxyConditionalRefreshHookTest {

    private static final String LAST_MODIFIED = "Tue, 24 Mar 2020 12:15:16 GMT";

    @Test
    // Hang guard, deliberately an order of magnitude above the real cost, NOT a
    // latency assertion: the method runs in ~0.25-0.5s on an idle machine. The
    // previous 5s bound (with a 4s latch wait below) timed out in 2 of 4 full
    // `mvn install -T8` runs on a loaded machine, at 5.3s elapsed — i.e. setup
    // starvation before the latch was even reached, which is scheduler noise on
    // a shared runner, not a regression. The latch below is what proves the
    // semantics; this only converts a genuine hang into a deterministic failure.
    @Timeout(30)
    void conditionalRefreshFiresPackumentWriteHookWhenEtagChanged() throws Exception {
        final String name = "asdas";
        final AtomicInteger upstreamCalls = new AtomicInteger();
        final AtomicReference<String> ifNoneMatchSeen = new AtomicReference<>();
        final byte[] packument = new TestResource("json/original.json").asBytes();
        final Slice upstream = (line, headers, body) -> {
            // Same ordering rule as NpmProxyConditionalRefreshTest's stub: record
            // the request first, publish the counter last, so a reader that
            // polls on the counter cannot observe a stale header value.
            ifNoneMatchSeen.set(headerValue(headers, "If-None-Match"));
            upstreamCalls.incrementAndGet();
            // Always 200 with a NEW ETag — simulates upstream content that
            // genuinely changed since the stored ETag was captured (never
            // 304 Not Modified).
            return ResponseBuilder.ok()
                .header("Last-Modified", LAST_MODIFIED)
                .header("ETag", "\"new-etag\"")
                .body(packument)
                .completedFuture();
        };

        final Storage storage = new InMemoryStorage();
        // Pre-populate storage directly (bypassing the normal cold-fetch
        // path) with STALE metadata that carries a stored upstream ETag —
        // the exact precondition conditionalRefresh's ETag-branch requires
        // (metadata.upstreamEtag().isPresent() && remote instanceof
        // HttpNpmRemote). No sleep/TTL-crossing needed: the fixture is
        // already "2 hours old" the moment the test runs.
        final RxNpmProxyStorage prep = new RxNpmProxyStorage(new RxStorageWrapper(storage));
        prep.save(new NpmPackage(
            name,
            new String(packument, java.nio.charset.StandardCharsets.UTF_8),
            new NpmPackage.Metadata(LAST_MODIFIED, OffsetDateTime.now().minus(2, ChronoUnit.HOURS))
        )).blockingAwait();
        prep.saveMetadataOnly(
            name,
            new NpmPackage.Metadata(
                LAST_MODIFIED,
                OffsetDateTime.now().minus(2, ChronoUnit.HOURS),
                null, null, "\"stale-etag\""
            )
        ).blockingAwait();

        final CountDownLatch hookFired = new CountDownLatch(1);
        final AtomicReference<String> hookedName = new AtomicReference<>();
        final NpmProxy npm = new NpmProxy(
            storage,
            upstream,
            Duration.ofHours(1),
            null,
            hooked -> {
                hookedName.set(hooked);
                hookFired.countDown();
            },
            null
        );

        // Stale-while-revalidate: this returns the STALE metadata
        // immediately and kicks off backgroundRefresh() asynchronously.
        npm.getPackageMetadataOnly(name).blockingGet();

        MatcherAssert.assertThat(
            "packument-write hook must fire on the ETag-changed conditional "
                + "refresh path (WS5.2) — without it, invalidateAfterProxyRefresh "
                + "never runs and a stale FilteredMetadataCache envelope survives "
                + "a genuine upstream content change",
            hookFired.await(20, TimeUnit.SECONDS),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "hook must receive the refreshed package's name",
            hookedName.get(),
            new IsEqual<>(name)
        );
        MatcherAssert.assertThat(
            "conditional refresh must have sent the stored ETag as If-None-Match",
            ifNoneMatchSeen.get(),
            new IsEqual<>("\"stale-etag\"")
        );
    }

    private static String headerValue(final Headers headers, final String name) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(h -> name.equalsIgnoreCase(h.getKey()))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElse(null);
    }
}
