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
package com.auto1.pantera.metrics;

import com.auto1.pantera.http.log.EcsLogger;
import io.micrometer.core.instrument.Counter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics and WARN emission for dropped {@code ProxyArtifactEvent} /
 * {@code ArtifactEvent} queue entries.
 *
 * <p>Used by every adapter that writes to a bounded
 * {@link java.util.concurrent.LinkedBlockingQueue} of metadata events (see
 * {@code MetadataEventQueues#proxyEventQueues}). When the per-repo queue is
 * saturated, {@link java.util.Queue#offer(Object)} returns {@code false};
 * call {@link #recordDropped(String)} to:</p>
 * <ol>
 *   <li>emit one WARN at {@code com.auto1.pantera.scheduling.events} with
 *       {@code event.action=queue_overflow} / {@code event.outcome=failure}
 *       and {@code repository.name=&lt;repo&gt;} — no stack trace;</li>
 *   <li>bump the Micrometer counter {@code pantera.events.queue.dropped}
 *       tagged with {@code queue=&lt;repo&gt;} when
 *       {@link MicrometerMetrics} is initialised.</li>
 * </ol>
 *
 * <p>The event itself is silently dropped — callers MUST NOT throw. This
 * class exists so a background-queue back-pressure event cannot escape the
 * serve path and cascade into 503 / 500 responses (forensic §1.6, §1.7
 * F1.1; WI-00 in v2.2 target-architecture doc).</p>
 *
 * @since 2.1.4
 */
public final class EventsQueueMetrics {

    /**
     * Counter name — visible on the Prometheus scrape endpoint as
     * {@code pantera_events_queue_dropped_total{queue="&lt;repo&gt;"}}.
     */
    public static final String COUNTER_NAME = "pantera.events.queue.dropped";

    /**
     * Process-wide drop tally (across all repos). Exposed for diagnostic
     * tests that run without a {@link io.micrometer.core.instrument.MeterRegistry}.
     */
    private static final AtomicLong DROP_COUNT = new AtomicLong();

    private EventsQueueMetrics() {
        // utility
    }

    /**
     * Record one dropped metadata event for {@code repoName}.
     *
     * <p>Emits a single WARN log line and increments the
     * {@code pantera.events.queue.dropped{queue=&lt;repoName&gt;}} counter.
     * Never throws.</p>
     *
     * @param repoName Repository whose queue overflowed
     */
    public static void recordDropped(final String repoName) {
        final long total = DROP_COUNT.incrementAndGet();
        EcsLogger.warn("com.auto1.pantera.scheduling.events")
            .message("event queue full — dropping event")
            .eventCategory("process")
            .eventAction("queue_overflow")
            .eventOutcome("failure")
            .field("repository.name", repoName == null ? "unknown" : repoName)
            .field("pantera.events.queue.drop_count", total)
            .log();
        if (MicrometerMetrics.isInitialized()) {
            try {
                Counter.builder(COUNTER_NAME)
                    .description(
                        "Metadata events dropped because the per-repo bounded"
                        + " ProxyArtifactEvent/ArtifactEvent queue was full"
                    )
                    .tag("queue", repoName == null ? "unknown" : repoName)
                    .register(MicrometerMetrics.getInstance().getRegistry())
                    .increment();
            } catch (final RuntimeException ignored) {
                // metrics registration must never escape the serve path
            }
        }
    }

    /**
     * Cumulative count of dropped events across all repos since JVM start.
     * Used by tests to assert that a drop actually happened.
     *
     * @return Monotonic drop total
     */
    public static long dropCount() {
        return DROP_COUNT.get();
    }
}
