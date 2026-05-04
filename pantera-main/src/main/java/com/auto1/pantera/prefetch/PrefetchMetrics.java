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
package com.auto1.pantera.prefetch;

import com.auto1.pantera.metrics.MicrometerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prefetch coordinator metrics — dual-purpose:
 *
 * <ol>
 *   <li><b>24h sliding window</b> per (repo, bucket-key) used by the stats
 *       panel API. Events are stored in a {@link ConcurrentSkipListMap} keyed
 *       by event {@link Instant}; reads sum entries newer than {@code now-24h}
 *       and prune older entries inline so memory stays bounded.</li>
 *   <li><b>Prometheus counters/gauges/histograms</b> registered through
 *       {@link MicrometerMetrics} when a registry exists. When the registry is
 *       absent (test environment), recording calls are silently no-ops — the
 *       in-memory window still works.</li>
 * </ol>
 *
 * <p>All public mutators are thread-safe: per-(repo,bucket) windows use
 * {@link ConcurrentSkipListMap} of {@link AtomicLong}, and the inflight
 * gauge backing values are {@link AtomicLong}.</p>
 *
 * <p>Spec metric names (Phase 4 §8):
 * <ul>
 *   <li>{@code pantera_prefetch_dispatched_total{repo, ecosystem}}</li>
 *   <li>{@code pantera_prefetch_completed_total{repo, ecosystem, outcome}}</li>
 *   <li>{@code pantera_prefetch_dropped_total{repo, reason}}</li>
 *   <li>{@code pantera_prefetch_inflight{repo}}</li>
 *   <li>{@code pantera_prefetch_parse_duration_seconds{ecosystem}}</li>
 *   <li>{@code pantera_prefetch_fetch_duration_seconds{repo}}</li>
 * </ul>
 *
 * @since 2.2.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidFieldNameMatchingMethodName"})
public final class PrefetchMetrics {

    /**
     * Sliding window length — 24 hours.
     */
    private static final Duration WINDOW = Duration.ofHours(24);

    /**
     * Bucket key for "dispatched" events.
     */
    private static final String BUCKET_DISPATCHED = "dispatched";

    /**
     * Bucket key for "completed" events. Suffixed with outcome.
     */
    private static final String BUCKET_COMPLETED = "completed:";

    /**
     * Bucket key for "dropped" events. Suffixed with reason.
     */
    private static final String BUCKET_DROPPED = "dropped:";

    /**
     * Outcome value for a successful prefetch (used by {@link #lastFetchAt}).
     */
    private static final String OUTCOME_SUCCESS = "success";

    private final Clock clock;

    /**
     * Per-repo, per-bucket sliding windows.
     * Outer key: repo name. Inner: bucket-key (e.g. "dispatched", "completed:success").
     * Inner-inner: event-instant -> count (almost always 1, but AtomicLong allows
     * coalescing concurrent inserts at the same instant).
     */
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentSkipListMap<Instant, AtomicLong>>>
        windows = new ConcurrentHashMap<>();

    /**
     * Per-repo last-successful-fetch timestamps (for the stats panel).
     */
    private final ConcurrentMap<String, AtomicLong> lastFetch = new ConcurrentHashMap<>();

    /**
     * Per-repo inflight counters (gauge backing values).
     */
    private final ConcurrentMap<String, AtomicLong> inflight = new ConcurrentHashMap<>();

    /**
     * Default constructor — uses the system UTC clock.
     */
    public PrefetchMetrics() {
        this(Clock.systemUTC());
    }

    /**
     * Test seam: inject a fake clock to drive the sliding window deterministically.
     *
     * @param clock Clock to read "now" from.
     */
    public PrefetchMetrics(final Clock clock) {
        this.clock = clock;
    }

    // ========== Mutators ==========

    /**
     * Record one dispatched task.
     *
     * @param repo Originating repo name.
     * @param ecosystem Ecosystem ("maven", "npm", ...).
     */
    public void dispatched(final String repo, final String ecosystem) {
        record(repo, BUCKET_DISPATCHED);
        if (MicrometerMetrics.isInitialized()) {
            Counter.builder("pantera.prefetch.dispatched")
                .description("Total prefetch tasks dispatched")
                .tags("repo", repo, "ecosystem", ecosystem)
                .register(MicrometerMetrics.getInstance().getRegistry())
                .increment();
        }
    }

    /**
     * Record one completed task with its outcome.
     *
     * @param repo Originating repo name.
     * @param ecosystem Ecosystem ("maven", "npm", ...).
     * @param outcome Outcome label ({@code "success"}, {@code "error"}, {@code "timeout"}, ...).
     */
    public void completed(final String repo, final String ecosystem, final String outcome) {
        record(repo, BUCKET_COMPLETED + outcome);
        if (OUTCOME_SUCCESS.equals(outcome)) {
            this.lastFetch
                .computeIfAbsent(repo, key -> new AtomicLong(0L))
                .set(this.clock.instant().toEpochMilli());
        }
        if (MicrometerMetrics.isInitialized()) {
            Counter.builder("pantera.prefetch.completed")
                .description("Total prefetch tasks completed")
                .tags("repo", repo, "ecosystem", ecosystem, "outcome", outcome)
                .register(MicrometerMetrics.getInstance().getRegistry())
                .increment();
        }
    }

    /**
     * Record one dropped task with its reason.
     *
     * @param repo Originating repo name.
     * @param reason Drop reason label ({@code "queue_full"}, {@code "circuit_open"}, ...).
     */
    public void dropped(final String repo, final String reason) {
        record(repo, BUCKET_DROPPED + reason);
        if (MicrometerMetrics.isInitialized()) {
            Counter.builder("pantera.prefetch.dropped")
                .description("Total prefetch tasks dropped")
                .tags("repo", repo, "reason", reason)
                .register(MicrometerMetrics.getInstance().getRegistry())
                .increment();
        }
    }

    /**
     * Get the inflight gauge handle for a repo.
     *
     * @param repo Repo name.
     * @return Inflight handle with {@code inc()} / {@code dec()} methods.
     */
    public InflightHandle inflight(final String repo) {
        return new InflightHandle(repo, inflightCounter(repo));
    }

    /**
     * Record parse duration for an ecosystem.
     *
     * @param ecosystem Ecosystem ("maven", "npm", ...).
     * @param duration Parse duration.
     */
    public void recordParse(final String ecosystem, final Duration duration) {
        if (MicrometerMetrics.isInitialized()) {
            DistributionSummary.builder("pantera.prefetch.parse.duration.seconds")
                .description("Prefetch parse duration in seconds")
                .tags("ecosystem", ecosystem)
                .baseUnit("seconds")
                .register(MicrometerMetrics.getInstance().getRegistry())
                .record(duration.toNanos() / 1_000_000_000.0);
        }
    }

    /**
     * Record upstream fetch duration for a repo.
     *
     * @param repo Repo name.
     * @param duration Fetch duration.
     */
    public void recordFetch(final String repo, final Duration duration) {
        if (MicrometerMetrics.isInitialized()) {
            DistributionSummary.builder("pantera.prefetch.fetch.duration.seconds")
                .description("Prefetch upstream fetch duration in seconds")
                .tags("repo", repo)
                .baseUnit("seconds")
                .register(MicrometerMetrics.getInstance().getRegistry())
                .record(duration.toNanos() / 1_000_000_000.0);
        }
    }

    // ========== Stats-panel readers ==========

    /**
     * Sum of dispatched events for {@code repo} within the last 24h.
     * Pruning happens inline.
     *
     * @param repo Repo name.
     * @return Count of dispatched events in the window.
     */
    public long dispatchedCount(final String repo) {
        return countBucket(repo, BUCKET_DISPATCHED);
    }

    /**
     * Sum of completed events with the given outcome for {@code repo} within the last 24h.
     *
     * @param repo Repo name.
     * @param outcome Outcome label.
     * @return Count of completed events with this outcome in the window.
     */
    public long completedCount(final String repo, final String outcome) {
        return countBucket(repo, BUCKET_COMPLETED + outcome);
    }

    /**
     * Sum of dropped events with the given reason for {@code repo} within the last 24h.
     *
     * @param repo Repo name.
     * @param reason Drop reason label.
     * @return Count of dropped events with this reason in the window.
     */
    public long droppedCount(final String repo, final String reason) {
        return countBucket(repo, BUCKET_DROPPED + reason);
    }

    /**
     * Most recent successful fetch timestamp for {@code repo}, if any.
     *
     * @param repo Repo name.
     * @return Last successful fetch instant, or empty if none has been recorded.
     */
    public Optional<Instant> lastFetchAt(final String repo) {
        final AtomicLong stamp = this.lastFetch.get(repo);
        if (stamp == null || stamp.get() == 0L) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(stamp.get()));
    }

    // ========== Internals ==========

    private void record(final String repo, final String bucket) {
        final Instant now = this.clock.instant();
        bucketWindow(repo, bucket)
            .computeIfAbsent(now, key -> new AtomicLong(0L))
            .incrementAndGet();
        // Prune opportunistically on every write so the window stays bounded
        // even on repos that are never read by the stats panel.
        prune(repo, bucket, now);
    }

    private long countBucket(final String repo, final String bucket) {
        final ConcurrentMap<String, ConcurrentSkipListMap<Instant, AtomicLong>> perBucket =
            this.windows.get(repo);
        if (perBucket == null) {
            return 0L;
        }
        final ConcurrentSkipListMap<Instant, AtomicLong> window = perBucket.get(bucket);
        if (window == null) {
            return 0L;
        }
        final Instant now = this.clock.instant();
        final Instant cutoff = now.minus(WINDOW);
        // Prune inline.
        final NavigableMap<Instant, AtomicLong> expired = window.headMap(cutoff, false);
        expired.clear();
        long total = 0L;
        for (final Map.Entry<Instant, AtomicLong> entry : window.tailMap(cutoff, true).entrySet()) {
            total += entry.getValue().get();
        }
        return total;
    }

    private ConcurrentSkipListMap<Instant, AtomicLong> bucketWindow(
        final String repo, final String bucket
    ) {
        return this.windows
            .computeIfAbsent(repo, key -> new ConcurrentHashMap<>())
            .computeIfAbsent(bucket, key -> new ConcurrentSkipListMap<>());
    }

    private void prune(final String repo, final String bucket, final Instant now) {
        final Instant cutoff = now.minus(WINDOW);
        final ConcurrentMap<String, ConcurrentSkipListMap<Instant, AtomicLong>> perBucket =
            this.windows.get(repo);
        if (perBucket == null) {
            return;
        }
        final ConcurrentSkipListMap<Instant, AtomicLong> window = perBucket.get(bucket);
        if (window == null) {
            return;
        }
        window.headMap(cutoff, false).clear();
    }

    private AtomicLong inflightCounter(final String repo) {
        return this.inflight.computeIfAbsent(repo, key -> {
            final AtomicLong gauge = new AtomicLong(0L);
            if (MicrometerMetrics.isInitialized()) {
                Gauge.builder("pantera.prefetch.inflight", gauge, AtomicLong::get)
                    .description("In-flight prefetch tasks per repo")
                    .tags("repo", repo)
                    .register(MicrometerMetrics.getInstance().getRegistry());
            }
            return gauge;
        });
    }

    /**
     * Increment / decrement handle for the {@code pantera_prefetch_inflight} gauge.
     */
    public static final class InflightHandle {
        private final String repo;
        private final AtomicLong gauge;

        InflightHandle(final String repo, final AtomicLong gauge) {
            this.repo = repo;
            this.gauge = gauge;
        }

        /** Repo name this handle tracks. */
        public String repo() {
            return this.repo;
        }

        /** Increment the gauge. */
        public void inc() {
            this.gauge.incrementAndGet();
        }

        /** Decrement the gauge (never negative). */
        public void dec() {
            this.gauge.updateAndGet(curr -> curr > 0L ? curr - 1L : 0L);
        }

        /** Current gauge value. */
        public long get() {
            return this.gauge.get();
        }
    }
}
