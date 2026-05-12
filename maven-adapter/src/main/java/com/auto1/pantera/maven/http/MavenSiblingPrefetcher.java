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
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.cache.ProxyCacheWriter;
import com.auto1.pantera.http.fault.Fault.ChecksumAlgo;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Track 4 sibling prefetcher: after a Maven primary lands in cache, warm the
 * companion artifact (typically the {@code .jar} ↔ {@code .pom} partner) in
 * the background so the next request is a cache hit.
 *
 * <p>Real {@code mvn dependency:resolve} walks request {@code .pom} first and
 * the matching {@code .jar} milliseconds later (and Gradle the reverse, plus
 * {@code .module}). Without sibling prefetch each pair takes two cold
 * upstream RTTs in series. With sibling prefetch, the second request hits a
 * cache populated while the first response was still being consumed by mvn.
 *
 * <h2>What counts as a sibling</h2>
 * <p>Strictly the same coordinate (group + artifactId + version), different
 * extension. The current set is conservative:</p>
 * <ul>
 *   <li>{@code .jar} &harr; {@code .pom}</li>
 * </ul>
 * <p>Sources / javadoc / Gradle {@code .module} are intentionally NOT
 * prefetched by default — they are only requested by a subset of clients
 * and prefetching them inflates upstream amplification without a payoff for
 * the common dependency-resolve walk.</p>
 *
 * <h2>Hot-path cost</h2>
 * <p>O(1) extension swap + a {@link Storage#exists} check (Caffeine-backed
 * filesystem layer) + an {@link ExecutorService#execute} on a bounded
 * single-thread executor. Heavy lifting (upstream fetch + integrity verify +
 * primary+sidecar commit) runs on the executor, never on the response
 * thread. A queue-full case drops the prefetch and increments
 * {@link #droppedTotal()} — the foreground response is never affected.</p>
 *
 * <h2>Dedup</h2>
 * <p>An in-flight set keyed by the sibling's storage key suppresses
 * duplicate fetches when two near-simultaneous primaries derive the same
 * partner. The entry is removed after the prefetch terminates (success or
 * failure).</p>
 *
 * @since 2.2.0
 */
public final class MavenSiblingPrefetcher {

    /** Companion-pair table: primary extension &rarr; sibling extension. */
    private static final Map<String, String> SIBLINGS = Map.of(
        ".jar", ".pom",
        ".pom", ".jar"
    );

    /** Counter feeding the prefetch worker thread name. */
    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    /** Upstream slice used to fetch the sibling primary + its sha1. */
    private final Slice upstream;

    /** Storage used to short-circuit when the sibling is already cached. */
    private final Storage cache;

    /** Writer that performs the same verify-then-commit dance as foreground. */
    private final ProxyCacheWriter writer;

    /** Repository name used for log fields. */
    private final String repoName;

    /** Upstream base URL — informational, used as the {@code url.full} field. */
    private final String upstreamUrl;

    /** Bounded executor that runs the per-sibling fetch + verify + commit. */
    private final ExecutorService executor;

    /** Per-key in-flight guard suppresses duplicate concurrent prefetches. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** Counter for prefetches dropped because the executor queue was full. */
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Build a sibling prefetcher backed by a single-thread bounded executor.
     * The single-thread choice is intentional: most {@code mvn} walks queue
     * dozens of prefetches in a short burst; a single worker drains the
     * queue without parallel upstream stress, and the upstream HTTP client
     * already pipelines on H2.
     *
     * @param upstream    Remote Maven slice (same one the foreground cache
     *                    miss flow uses).
     * @param cache       Storage shared with the foreground slice.
     * @param writer      Cache writer (Track 3 atomic primary+sidecar).
     * @param repoName    Repository name for log fields.
     * @param upstreamUrl Base upstream URL for log fields.
     */
    public MavenSiblingPrefetcher(
        final Slice upstream, final Storage cache,
        final ProxyCacheWriter writer, final String repoName,
        final String upstreamUrl
    ) {
        this(upstream, cache, writer, repoName, upstreamUrl, defaultExecutor(repoName));
    }

    /**
     * Test-friendly constructor — injectable executor so unit tests can run
     * the prefetch synchronously or stub it out entirely.
     */
    MavenSiblingPrefetcher(
        final Slice upstream, final Storage cache,
        final ProxyCacheWriter writer, final String repoName,
        final String upstreamUrl, final ExecutorService executor
    ) {
        this.upstream = upstream;
        this.cache = cache;
        this.writer = writer;
        this.repoName = repoName;
        this.upstreamUrl = upstreamUrl;
        this.executor = executor;
    }

    /**
     * Build the default executor: one daemon worker, no queue cap — but the
     * inFlight set bounds concurrent work to one fetch per sibling key.
     */
    private static ExecutorService defaultExecutor(final String repoName) {
        final ThreadFactory tf = r -> {
            final Thread t = new Thread(r,
                "pantera-mvn-sibling-prefetch-" + repoName + "-"
                + THREAD_COUNTER.incrementAndGet()
            );
            t.setDaemon(true);
            return t;
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    /**
     * Triggered by the foreground slice immediately after a successful
     * primary commit. Derives the companion key, short-circuits when it is
     * already cached, and queues the fetch on the prefetch executor. Never
     * throws — every failure path is logged and swallowed so the
     * foreground response thread remains unaffected.
     *
     * @param primaryKey Cache key of the primary that just landed.
     */
    public void onPrimaryCached(final Key primaryKey) {
        final List<Key> siblings = derive(primaryKey);
        for (final Key sibling : siblings) {
            this.maybeFetch(sibling);
        }
    }

    /**
     * Compute the sibling keys for a primary. Public for unit tests; the
     * derivation is intentionally pure — no I/O.
     */
    static List<Key> derive(final Key primary) {
        final String path = primary.string();
        final int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return List.of();
        }
        final String ext = path.substring(dot).toLowerCase(Locale.ROOT);
        final String partnerExt = SIBLINGS.get(ext);
        if (partnerExt == null) {
            return List.of();
        }
        final List<Key> out = new ArrayList<>(1);
        out.add(new Key.From(path.substring(0, dot) + partnerExt));
        return out;
    }

    /** Cumulative drop count (queue saturation). Exposed for tests/metrics. */
    public long droppedTotal() {
        return this.dropped.get();
    }

    /** Drain in-flight prefetches and stop the executor. */
    public void stop() {
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(5L, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (final InterruptedException ie) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Decide whether to enqueue a prefetch and, if so, hand the actual work
     * off to {@link #executor}. The hot-path side is a non-blocking
     * {@code exists} check + a {@code Set.add} + an {@code executor.execute};
     * everything else runs async.
     */
    private void maybeFetch(final Key sibling) {
        if (!this.inFlight.add(sibling.string())) {
            return;
        }
        try {
            this.executor.execute(() -> this.fetchInBackground(sibling));
        } catch (final RejectedExecutionException rex) {
            this.inFlight.remove(sibling.string());
            this.dropped.incrementAndGet();
            EcsLogger.debug("com.auto1.pantera.maven.prefetch")
                .message("Sibling prefetch dropped — executor rejected")
                .field("repository.name", this.repoName)
                .field("url.path", sibling.string())
                .field("prefetch.dropped_total", this.dropped.get())
                .eventCategory("process")
                .eventAction("sibling_prefetch_drop")
                .log();
        }
    }

    /**
     * Background prefetch body. Re-checks {@code cache.exists} under the
     * worker thread (the foreground may have populated the sibling between
     * the enqueue and the dequeue), then runs the same fetch + verify +
     * commit dance the foreground slice uses.
     */
    private void fetchInBackground(final Key sibling) {
        try {
            if (this.cache.exists(sibling).toCompletableFuture().join()) {
                return;
            }
            final RequestLine line = new RequestLine("GET", "/" + sibling.string());
            final Map<ChecksumAlgo, Supplier<CompletionStage<Optional<InputStream>>>> sidecars =
                new EnumMap<>(ChecksumAlgo.class);
            sidecars.put(ChecksumAlgo.SHA1, () -> this.fetchSidecar(line, ".sha1"));
            this.writer.writeWithSidecars(
                sibling,
                this.upstreamUrl + "/" + sibling.string(),
                () -> this.fetchPrimary(line),
                sidecars,
                null
            ).toCompletableFuture().join();
        } catch (final Throwable err) {
            // Sibling prefetch is best-effort; we log at DEBUG because
            // 404s on companion artifacts are the dominant failure mode
            // (a -sources.jar may not exist for many artifacts even
            // though the .jar does) and are expected, not exceptional.
            EcsLogger.debug("com.auto1.pantera.maven.prefetch")
                .message("Sibling prefetch failed")
                .field("repository.name", this.repoName)
                .field("url.path", sibling.string())
                .field("error.message", String.valueOf(err.getMessage()))
                .eventCategory("process")
                .eventAction("sibling_prefetch")
                .log();
        } finally {
            this.inFlight.remove(sibling.string());
        }
    }

    /**
     * Fetch a sibling's primary body as an {@link InputStream} for the
     * writer. Mirrors {@code CachedProxySlice.fetchPrimaryBody} but blocks
     * — we are running on the prefetch executor, not the response thread.
     */
    private CompletionStage<InputStream> fetchPrimary(final RequestLine line) {
        return this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                if (!resp.status().success()) {
                    resp.body().asBytesFuture();
                    throw new IllegalStateException(
                        "Upstream HTTP " + resp.status().code()
                    );
                }
                final byte[] bytes = resp.body().asBytesFuture().join();
                return new ByteArrayInputStream(bytes);
            });
    }

    /** Fetch a sibling sidecar (.sha1), absent on non-success. */
    private CompletionStage<Optional<InputStream>> fetchSidecar(
        final RequestLine primary, final String extension
    ) {
        final String path = primary.uri().getPath() + extension;
        final RequestLine sidecarLine = new RequestLine(primary.method().value(), path);
        return this.upstream.response(sidecarLine, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> {
                if (!resp.status().success()) {
                    return resp.body().asBytesFuture()
                        .thenApply(ignored -> Optional.<InputStream>empty());
                }
                return resp.body().asBytesFuture()
                    .thenApply(bytes -> Optional.<InputStream>of(
                        new ByteArrayInputStream(bytes)
                    ));
            })
            .exceptionally(ignored -> Optional.<InputStream>empty());
    }
}
