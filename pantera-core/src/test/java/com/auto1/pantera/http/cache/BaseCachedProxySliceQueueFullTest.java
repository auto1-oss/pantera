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
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.metrics.EventsQueueMetrics;
import com.auto1.pantera.scheduling.ProxyArtifactEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity guard for the v2.1.4 WI-00 Queue-full hotfix: {@link BaseCachedProxySlice}
 * (the shared base behind {@code maven-adapter/CachedProxySlice}) must increment
 * {@link EventsQueueMetrics#dropCount()} on {@link Queue#offer(Object)} overflow,
 * matching the npm adapter pattern exercised by
 * {@code DownloadAssetSliceQueueFullTest}.
 *
 * <p>Pre-fills a bounded {@link LinkedBlockingQueue} to capacity so the next
 * {@code offer()} returns {@code false}, fires a GET through a non-storage-backed
 * subclass (so the {@code fetchDirect} path runs and calls
 * {@code enqueueEvent}), and asserts:
 * <ol>
 *   <li>The serve path returns HTTP 200 — exception must never escape.</li>
 *   <li>{@link EventsQueueMetrics#dropCount()} advances by at least one — the
 *       drop was observed by ops plumbing.</li>
 * </ol>
 *
 * <p>Before the production fix at {@code BaseCachedProxySlice.java:1116} was
 * updated to check the {@code offer()} return value, the drop was silently
 * swallowed and this test failed on the counter assertion.</p>
 *
 * @since 2.2.1
 */
final class BaseCachedProxySliceQueueFullTest {

    /**
     * Repo name used across the test — shows up as the {@code queue=} tag
     * on the {@code pantera.events.queue.dropped} counter.
     */
    private static final String RNAME = "test-repo-saturated";

    @Test
    void droppedEventOnFullQueueBumpsCounterAndServeReturnsOk() {
        // Bounded to 1 and pre-filled — any subsequent offer() MUST return false.
        final LinkedBlockingQueue<ProxyArtifactEvent> queue = new LinkedBlockingQueue<>(1);
        queue.add(new ProxyArtifactEvent(new Key.From("filler"), RNAME, "filler"));
        assertEquals(0, queue.remainingCapacity(), "queue is saturated before request");
        final long dropsBefore = EventsQueueMetrics.dropCount();
        final byte[] body = "payload".getBytes();
        final EventEnqueuingProxySlice slice = new EventEnqueuingProxySlice(
            // Upstream always succeeds — fetchDirect's success branch calls enqueueEvent.
            (line, headers, reqBody) -> CompletableFuture.completedFuture(
                ResponseBuilder.ok()
                    .header("Content-Type", "application/java-archive")
                    .body(body)
                    .build()
            ),
            queue
        );
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, "/com/example/foo/1.0/foo-1.0.jar"),
            Headers.EMPTY,
            Content.EMPTY
        ).join();
        // Serve path is unaffected — background queue failure MUST NOT escape.
        assertEquals(RsStatus.OK, response.status(), "serve path returns 200 despite full queue");
        // Drop counter advanced — ops has visibility into the overflow.
        final long drops = EventsQueueMetrics.dropCount() - dropsBefore;
        // EventsQueueMetrics.DROP_COUNT is a static AtomicLong shared across the
        // test JVM; other tests in the suite may also advance it, so tolerate >= 1.
        assertTrue(
            drops >= 1L,
            "EventsQueueMetrics drop counter must advance by at least 1 on offer() overflow,"
                + " observed delta=" + drops
        );
    }

    /**
     * Minimal concrete subclass of {@link BaseCachedProxySlice} that makes every
     * path non-cacheable (so {@code fetchDirect} is the serve flow) and emits a
     * {@link ProxyArtifactEvent} on every successful upstream response (so
     * {@code enqueueEvent} always attempts an {@code offer()}).
     */
    private static final class EventEnqueuingProxySlice extends BaseCachedProxySlice {

        EventEnqueuingProxySlice(
            final Slice upstream,
            final Queue<ProxyArtifactEvent> queue
        ) {
            super(
                upstream,
                Cache.NOP,
                RNAME,
                "test",
                "http://upstream",
                Optional.empty(),
                Optional.of(queue),
                ProxyCacheConfig.defaults()
            );
        }

        @Override
        protected boolean isCacheable(final String path) {
            // Force the fetchDirect branch, which calls enqueueEvent on 200.
            return false;
        }

        @Override
        protected Optional<ProxyArtifactEvent> buildArtifactEvent(
            final Key key,
            final Headers responseHeaders,
            final long size,
            final String owner
        ) {
            return Optional.of(new ProxyArtifactEvent(key, RNAME, owner));
        }
    }
}
