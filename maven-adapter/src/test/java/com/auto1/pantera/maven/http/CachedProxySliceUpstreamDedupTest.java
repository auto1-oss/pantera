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
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.maven.cooldown.MavenCooldownResponseFactory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M4 (analysis/plan/v1/PLAN.md): 50 concurrent clients requesting the
 * same uncached Maven primary must collapse to exactly one upstream
 * call. Pre-M4 each request fired its own
 * {@code fetchVerifyAndCache} which independently hit upstream, the
 * dominant cold-walk amplifier after M2's prefetch deletion.
 *
 * <p>The Finding #2 fix (commit 21232a5b1) covered the
 * {@code BaseCachedProxySlice.fetchAndCache} path; M4 extends the same
 * leader/follower coalescing into the Maven-specific custom path via
 * {@link com.auto1.pantera.http.cache.BaseCachedProxySlice#coalesceUpstream}.
 *
 * @since 2.2.0
 */
final class CachedProxySliceUpstreamDedupTest {

    private static final String PRIMARY_PATH = "/com/example/foo/1.0/foo-1.0.jar";
    private static final byte[] PRIMARY_BYTES =
        "fake-jar-bytes-for-dedup-test".getBytes(StandardCharsets.UTF_8);
    private static final int CONCURRENCY = 50;

    private Queue<ProxyArtifactEvent> events;

    @BeforeEach
    void init() {
        this.events = new LinkedList<>();
        CooldownResponseRegistry.instance()
            .register("maven-proxy", new MavenCooldownResponseFactory());
    }

    @Test
    void fiftyConcurrentClientsFireExactlyOneUpstreamCall() throws Exception {
        final AtomicInteger primaryHits = new AtomicInteger();
        final AtomicInteger sidecarHits = new AtomicInteger();
        // Slow upstream so concurrent followers really do park on the
        // leader's gate. Without this, the leader could finish before any
        // follower entered fetchVerifyAndCache and the test would not
        // exercise the coalescing path.
        final String sha1Hex = sha1Hex(PRIMARY_BYTES);
        final com.auto1.pantera.http.Slice upstream = (line, headers, body) -> {
            final String path = line.uri().getPath();
            if (path.endsWith(".sha1")) {
                sidecarHits.incrementAndGet();
                return CompletableFuture.completedFuture(
                    ResponseBuilder.ok().body(sha1Hex.getBytes(StandardCharsets.UTF_8)).build()
                );
            }
            primaryHits.incrementAndGet();
            try {
                Thread.sleep(150);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(
                ResponseBuilder.ok().body(PRIMARY_BYTES).build()
            );
        };
        final Storage storage = new InMemoryStorage();
        final CachedProxySlice slice = new CachedProxySlice(
            upstream,
            new com.auto1.pantera.asto.cache.FromStorageCache(storage),
            Optional.of(this.events), "maven-proxy",
            "https://repo.maven.apache.org/maven2", "maven-proxy",
            NoopCooldownService.INSTANCE, noopInspector(), Optional.of(storage)
        );
        final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(CONCURRENCY);
            final AtomicInteger successCount = new AtomicInteger();
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        final Response r = slice.response(
                            new RequestLine(RqMethod.GET, PRIMARY_PATH),
                            Headers.EMPTY, Content.EMPTY
                        ).join();
                        // Drain the response body to mirror production: the
                        // Vert.x HTTP server subscribes the publisher when it
                        // serialises the body to the wire. The leader's
                        // verificationOutcome only fires after its body drains,
                        // releasing followers from the single-flight gate.
                        if (r.status().code() == 200) {
                            r.body().asBytesFuture().toCompletableFuture().join();
                            successCount.incrementAndGet();
                        }
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            MatcherAssert.assertThat(
                "all clients finish within 10 s",
                done.await(10, TimeUnit.SECONDS),
                new IsEqual<>(true)
            );
            MatcherAssert.assertThat(
                "all 50 clients receive a 200",
                successCount.get(), new IsEqual<>(CONCURRENCY)
            );
            MatcherAssert.assertThat(
                "exactly one upstream primary call across 50 concurrent clients "
                    + "(pre-M4 each client fired its own fetchVerifyAndCache)",
                primaryHits.get(), new IsEqual<>(1)
            );
            // .sha1 is fetched once alongside the primary by the leader.
            // Followers serve from the cache and do not refetch the sidecar.
            MatcherAssert.assertThat(
                "exactly one upstream .sha1 fetch (leader's verify path)",
                sidecarHits.get(), new IsEqual<>(1)
            );
        } finally {
            pool.shutdownNow();
        }
    }

    private static String sha1Hex(final byte[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] digest = md.digest(data);
            final StringBuilder out = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (final java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 unavailable", ex);
        }
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
