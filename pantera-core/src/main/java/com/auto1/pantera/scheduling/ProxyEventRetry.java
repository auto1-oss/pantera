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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.metrics.EventsQueueMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.LongSupplier;

/**
 * Bounded retry of proxy package events whose artifact is not in storage
 * yet.
 *
 * <p>A proxy package processor reads the cached artifact to record it. The
 * event can reach the queue before the cache write is durable (a
 * stream-through cache commits only when the client has read the body), and
 * the artifact may never land at all: the client hung up, the checksum did
 * not match, the file was evicted. Putting the event straight back made the
 * processor look at it again on every run, forever.</p>
 *
 * <p>Here an event is put back at most {@link #MAX_ATTEMPTS} times, each
 * time with a longer wait (1 s doubling up to 60 s, about three minutes in
 * total). The attempt count and the wait travel with the event
 * ({@link ProxyArtifactEvent#retried(long)}), because Quartz builds a new
 * job instance for every run. Retries log at DEBUG; giving up logs one
 * WARN.</p>
 *
 * @since 2.2.14
 */
public final class ProxyEventRetry {

    /**
     * Times an event is put back before it is given up.
     */
    public static final int MAX_ATTEMPTS = 8;

    /**
     * Wait before the first retry, milliseconds.
     */
    private static final long BASE_DELAY = 1_000L;

    /**
     * Longest wait between two retries, milliseconds.
     */
    private static final long MAX_DELAY = 60_000L;

    /**
     * Queue the processor drains.
     */
    private final Queue<ProxyArtifactEvent> queue;

    /**
     * Current time in epoch millis.
     */
    private final LongSupplier clock;

    /**
     * Ctor.
     * @param queue Queue the processor drains
     */
    public ProxyEventRetry(final Queue<ProxyArtifactEvent> queue) {
        this(queue, System::currentTimeMillis);
    }

    /**
     * Ctor.
     * @param queue Queue the processor drains
     * @param clock Current time in epoch millis
     */
    public ProxyEventRetry(final Queue<ProxyArtifactEvent> queue, final LongSupplier clock) {
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * Take the events that may be processed now. An event still waiting for
     * its retry time goes back to the queue untouched; each queued event is
     * looked at once per call.
     * @param max Largest batch to return
     * @return Events to process
     */
    public List<ProxyArtifactEvent> due(final int max) {
        final long now = this.clock.getAsLong();
        final List<ProxyArtifactEvent> ready = new ArrayList<>(Math.min(max, 100));
        final List<ProxyArtifactEvent> waiting = new ArrayList<>(0);
        int budget = this.queue.size();
        while (ready.size() < max && budget > 0) {
            final ProxyArtifactEvent event = this.queue.poll();
            if (event == null) {
                break;
            }
            budget -= 1;
            if (event.notBefore() <= now) {
                ready.add(event);
            } else {
                waiting.add(event);
            }
        }
        for (final ProxyArtifactEvent event : waiting) {
            this.offer(event);
        }
        return ready;
    }

    /**
     * Put an event back because its artifact is not in storage yet, or give
     * it up when it has been put back {@link #MAX_ATTEMPTS} times.
     * @param event Event that could not be processed
     * @param logger Logger name of the calling processor
     * @return True if the event was queued for another attempt
     */
    public boolean again(final ProxyArtifactEvent event, final String logger) {
        final boolean queued;
        if (event.attempts() >= ProxyEventRetry.MAX_ATTEMPTS) {
            queued = false;
            EcsLogger.warn(logger)
                .traceContext(event.traceId(), event.clientIp())
                .message(
                    "Proxy artifact still not in storage after "
                        + ProxyEventRetry.MAX_ATTEMPTS
                        + " retries, giving up: it is served but not recorded in the artifact index"
                )
                .eventCategory("web")
                .eventAction("proxy_package_retry")
                .eventOutcome("failure")
                .field("event.reason", "retry_exhausted")
                .field("package.name", event.artifactKey().string())
                .field("repository.name", event.repoName())
                .field("log.source", "application")
                .log();
        } else {
            final long delay = Math.min(
                ProxyEventRetry.BASE_DELAY << event.attempts(), ProxyEventRetry.MAX_DELAY
            );
            queued = this.offer(event.retried(this.clock.getAsLong() + delay));
            EcsLogger.debug(logger)
                .traceContext(event.traceId(), event.clientIp())
                .message(
                    "Proxy artifact not in storage yet, retry "
                        + (event.attempts() + 1) + "/" + ProxyEventRetry.MAX_ATTEMPTS
                        + " in " + delay + " ms"
                )
                .eventCategory("web")
                .eventAction("proxy_package_retry")
                .field("package.name", event.artifactKey().string())
                .field("repository.name", event.repoName())
                .field("log.source", "application")
                .log();
        }
        return queued;
    }

    /**
     * Queue an event; a full queue drops it and counts the drop.
     * @param event Event
     * @return True if queued
     */
    private boolean offer(final ProxyArtifactEvent event) {
        final boolean queued = this.queue.offer(event);
        if (!queued) {
            EventsQueueMetrics.recordDropped(event.repoName());
        }
        return queued;
    }
}
