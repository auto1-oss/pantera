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
package com.auto1.pantera.api.v1;

import com.auto1.pantera.prefetch.PrefetchCoordinator;
import com.auto1.pantera.prefetch.PrefetchMetrics;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.time.Instant;
import java.util.Optional;

/**
 * Read-only stats handler for the prefetch subsystem.
 *
 * <p>Exposes a 24h sliding-window summary per repository so the
 * Repo edit "Performance" panel (Task 23) can render counts of
 * dispatched / completed / dropped events plus the most recent
 * successful fetch timestamp.</p>
 *
 * <p>Wraps {@link PrefetchMetrics} — a singleton owned by
 * {@code VertxMain.installPrefetch()} and passed in via the
 * {@link AsyncApiVerticle} constructor. When prefetch is not
 * wired (DB-less boot, tests without a coordinator) the
 * {@code metrics} field is {@code null} and every endpoint
 * returns zero counts so the UI degrades gracefully.</p>
 *
 * <p>JSON response shape:
 * <pre>{@code
 * {
 *   "repo": "maven_proxy",
 *   "window": "24h",
 *   "prefetched": 1247,
 *   "cooldown_blocked": 23,
 *   "dropped_queue_full": 4,
 *   "dropped_semaphore_saturated": 0,
 *   "dropped_dedup_in_flight": 0,
 *   "dropped_circuit_open": 0,
 *   "last_fetch_at": "2026-05-04T11:42:13Z"
 * }
 * }</pre>
 *
 * <p>The {@code last_fetch_at} field is omitted when no successful
 * fetch has been recorded for the repo. No auth gate — this is a
 * read-only stats endpoint behind the regular JWT bearer middleware.</p>
 *
 * @since 2.2.0
 */
public final class PrefetchStatsHandler {

    /**
     * Sliding window label echoed back to the client. Matches the
     * {@code WINDOW} constant in {@link PrefetchMetrics}.
     */
    private static final String WINDOW = "24h";

    /**
     * Prefetch metrics — nullable. When null (no coordinator wired),
     * every endpoint returns zero counts.
     */
    private final PrefetchMetrics metrics;

    /**
     * Ctor.
     *
     * @param metrics Prefetch metrics, or {@code null} when prefetch
     *     is not wired in the running JVM (DB-less boot, tests).
     */
    public PrefetchStatsHandler(final PrefetchMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Register the {@code GET /api/v1/repositories/:name/prefetch/stats}
     * route on the given router. The auth chain wired upstream in
     * {@link AsyncApiVerticle} already enforces JWT bearer auth on every
     * {@code /api/v1/*} path.
     *
     * @param router Vert.x router to register on.
     */
    public void register(final Router router) {
        router.get("/api/v1/repositories/:name/prefetch/stats")
            .handler(this::getStats);
    }

    /**
     * GET handler. Reads counts from {@link PrefetchMetrics} and shapes
     * the JSON response.
     *
     * @param ctx Routing context.
     */
    private void getStats(final RoutingContext ctx) {
        final String repo = ctx.pathParam("name");
        final JsonObject body = new JsonObject()
            .put("repo", repo)
            .put("window", WINDOW);
        final long prefetched;
        final long cooldownBlocked;
        final long droppedQueueFull;
        final long droppedSemaphore;
        final long droppedDedup;
        final long droppedCircuit;
        final Optional<Instant> lastFetch;
        if (this.metrics == null) {
            prefetched = 0L;
            cooldownBlocked = 0L;
            droppedQueueFull = 0L;
            droppedSemaphore = 0L;
            droppedDedup = 0L;
            droppedCircuit = 0L;
            lastFetch = Optional.empty();
        } else {
            prefetched = this.metrics.completedCount(
                repo, PrefetchMetrics.OUTCOME_FETCHED_200
            );
            cooldownBlocked = this.metrics.completedCount(
                repo, PrefetchMetrics.OUTCOME_COOLDOWN_BLOCKED
            );
            droppedQueueFull = this.metrics.droppedCount(
                repo, PrefetchCoordinator.REASON_QUEUE_FULL
            );
            droppedSemaphore = this.metrics.droppedCount(
                repo, PrefetchCoordinator.REASON_SEMAPHORE
            );
            droppedDedup = this.metrics.droppedCount(
                repo, PrefetchCoordinator.REASON_DEDUP
            );
            droppedCircuit = this.metrics.droppedCount(
                repo, PrefetchCoordinator.REASON_CIRCUIT_OPEN
            );
            lastFetch = this.metrics.lastFetchAt(repo);
        }
        body.put("prefetched", prefetched)
            .put("cooldown_blocked", cooldownBlocked)
            .put("dropped_queue_full", droppedQueueFull)
            .put("dropped_semaphore_saturated", droppedSemaphore)
            .put("dropped_dedup_in_flight", droppedDedup)
            .put("dropped_circuit_open", droppedCircuit);
        // ISO-8601 instant via Instant.toString() — e.g. 2026-05-04T11:42:13.123Z.
        // Field is omitted when no successful fetch has been recorded.
        lastFetch.ifPresent(stamp -> body.put("last_fetch_at", stamp.toString()));
        ctx.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end(body.encode());
    }
}
