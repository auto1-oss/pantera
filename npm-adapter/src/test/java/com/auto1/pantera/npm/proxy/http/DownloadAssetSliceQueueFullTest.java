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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.cooldown.api.CooldownDependency;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.impl.NoopCooldownService;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.metrics.EventsQueueMetrics;
import com.auto1.pantera.npm.misc.NextSafeAvailablePort;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.ext.web.client.WebClient;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Verifies that {@link DownloadAssetSlice} serves cache-hit responses with
 * HTTP 200 even when the background {@code ProxyArtifactEvent} queue is
 * saturated — the v2.1.4 WI-00 hotfix for the Queue-full cascade.
 *
 * <p>Pre-fills a bounded {@link LinkedBlockingQueue} to capacity (2 slots),
 * then fires 50 concurrent cache-hit GETs through a {@code DownloadAssetSlice}
 * wired to that already-full queue. Asserts every request returns HTTP 200
 * and no exception escapes the serve path. Drops are counted via
 * {@link EventsQueueMetrics#dropCount()}.</p>
 */
final class DownloadAssetSliceQueueFullTest {

    private static final String RNAME = "my-npm-saturated";

    private static final String TGZ =
        "@hello/simple-npm-project/-/@hello/simple-npm-project-1.0.1.tgz";

    private static final int CONCURRENT_REQUESTS = 50;

    private static final Vertx VERTX = Vertx.vertx();

    private int port;

    private LinkedBlockingQueue<ProxyArtifactEvent> packages;

    @BeforeEach
    void setUp() {
        this.port = new NextSafeAvailablePort().value();
        // Bounded to 2 so any second enqueue lands in the drop path.
        this.packages = new LinkedBlockingQueue<>(2);
    }

    @AfterAll
    static void tearDown() {
        DownloadAssetSliceQueueFullTest.VERTX.close();
    }

    @Test
    void fiftyConcurrentCacheHitsAllReturnOkDespiteFullQueue() throws Exception {
        // Pre-fill the queue to capacity — the next offer() MUST return false
        // without throwing. The serve path must remain HTTP 200.
        final Key sentinel = new Key.From("sentinel");
        this.packages.add(new ProxyArtifactEvent(sentinel, RNAME, "filler", Optional.empty()));
        this.packages.add(new ProxyArtifactEvent(sentinel, RNAME, "filler", Optional.empty()));
        MatcherAssert.assertThat(
            "Queue is at capacity before request burst",
            this.packages.remainingCapacity(),
            Matchers.is(0)
        );
        final Storage storage = new InMemoryStorage();
        this.saveCachedAsset(storage);
        final AssetPath path = new AssetPath("");
        final long dropsBefore = EventsQueueMetrics.dropCount();
        try (
            VertxSliceServer server = new VertxSliceServer(
                DownloadAssetSliceQueueFullTest.VERTX,
                new DownloadAssetSlice(
                    new NpmProxy(
                        storage,
                        new SliceSimple(ResponseBuilder.notFound().build())
                    ),
                    path, Optional.of(this.packages), RNAME, "npm-proxy",
                    NoopCooldownService.INSTANCE, noopInspector()
                ),
                this.port
            )
        ) {
            server.start();
            this.fire50ConcurrentRequestsAndAssertAllOk();
        }
        // After the burst the drop counter must have advanced — every
        // cache-hit attempted one enqueue on a full queue.
        final long drops = EventsQueueMetrics.dropCount() - dropsBefore;
        MatcherAssert.assertThat(
            "queue overflows incremented the drop counter at least once",
            drops,
            Matchers.greaterThanOrEqualTo(1L)
        );
    }

    private void fire50ConcurrentRequestsAndAssertAllOk()
        throws InterruptedException, ExecutionException, TimeoutException {
        final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        final List<Future<Integer>> results = new ArrayList<>(CONCURRENT_REQUESTS);
        final WebClient client = WebClient.create(DownloadAssetSliceQueueFullTest.VERTX);
        try {
            final String url = String.format(
                "http://127.0.0.1:%d/%s", this.port, DownloadAssetSliceQueueFullTest.TGZ
            );
            for (int i = 0; i < CONCURRENT_REQUESTS; i = i + 1) {
                results.add(pool.submit(() -> {
                    final CompletableFuture<Integer> future = new CompletableFuture<>();
                    client.getAbs(url).send(ar -> {
                        if (ar.succeeded()) {
                            future.complete(ar.result().statusCode());
                        } else {
                            future.completeExceptionally(ar.cause());
                        }
                    });
                    return future.get(30, TimeUnit.SECONDS);
                }));
            }
            int okCount = 0;
            for (final Future<Integer> result : results) {
                final Integer code = result.get(60, TimeUnit.SECONDS);
                MatcherAssert.assertThat(
                    "Every request completes with HTTP 200 (no exception escapes)",
                    code, Matchers.is(200)
                );
                okCount = okCount + 1;
            }
            MatcherAssert.assertThat(
                "All 50 concurrent cache-hit GETs returned HTTP 200",
                okCount, Matchers.is(CONCURRENT_REQUESTS)
            );
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
            client.close();
        }
    }

    private void saveCachedAsset(final Storage storage) {
        storage.save(
            new Key.From(DownloadAssetSliceQueueFullTest.TGZ),
            new Content.From(
                new TestResource(
                    String.format("storage/%s", DownloadAssetSliceQueueFullTest.TGZ)
                ).asBytes()
            )
        ).join();
        storage.save(
            new Key.From(String.format("%s.meta", DownloadAssetSliceQueueFullTest.TGZ)),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .build()
                    .toString()
                    .getBytes()
            )
        ).join();
    }

    private static CooldownInspector noopInspector() {
        return new CooldownInspector() {
            @Override
            public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }
}
