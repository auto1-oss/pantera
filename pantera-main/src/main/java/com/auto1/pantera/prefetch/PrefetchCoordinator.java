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

import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.http.cache.NegativeCacheKey;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.runtime.PrefetchTuning;
import com.auto1.pantera.settings.runtime.RuntimeSettingsCache;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded asynchronous dispatcher for prefetch tasks.
 *
 * <p>Lifecycle of a {@link PrefetchTask}:
 * <ol>
 *   <li>{@link #submit(PrefetchTask)} short-circuits with a {@code dropped}
 *       metric if the {@link PrefetchCircuitBreaker} is open or if an
 *       identical (repo + coordinate path) task is already in-flight.</li>
 *   <li>The task is enqueued via {@link BlockingQueue#offer(Object)} on the
 *       bounded {@link ArrayBlockingQueue}. A full queue records the drop
 *       and feeds the breaker.</li>
 *   <li>A worker thread pulls the task, then in order: checks the
 *       {@link NegativeCache}, evaluates the {@link CooldownGate}, acquires
 *       the per-upstream and global {@link Semaphore}s, then fires the
 *       upstream GET via the injected {@link UpstreamCaller}.</li>
 *   <li>Outcome is reported through {@link PrefetchMetrics#completed(String, String, String)}
 *       and the in-flight set is cleared in every exit path.</li>
 * </ol>
 *
 * <p>Settings hot-reload: {@link RuntimeSettingsCache#addListener(String, com.auto1.pantera.settings.runtime.SettingsChangeListener)}
 * with the {@code prefetch.} prefix swaps the queue and semaphores
 * atomically; the worker pool is replaced when {@code worker_threads}
 * changes. The new queue starts empty &mdash; tasks already inside the old
 * queue are drained and discarded; in-flight upstream calls finish on the
 * old semaphores (which are then garbage-collected).</p>
 *
 * <p><b>Thread-safety:</b> in-flight set is a {@link ConcurrentHashMap}
 * key set; queue/semaphore handles live behind an
 * {@link AtomicReference} swapped under {@code applyTuning}'s monitor.</p>
 *
 * @since 2.2.0
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.GodClass", "PMD.ExcessiveImports"})
public final class PrefetchCoordinator {

    /**
     * Drop reasons. Kept here so callers and tests can refer to constants
     * instead of typo-prone string literals.
     */
    public static final String REASON_CIRCUIT_OPEN = "circuit_open";
    public static final String REASON_DEDUP = "dedup_in_flight";
    public static final String REASON_QUEUE_FULL = "queue_full";
    public static final String REASON_SEMAPHORE = "semaphore_saturated";
    /**
     * Drop reason recorded for tasks that were sitting in the bounded queue
     * when {@link #applyTuning(PrefetchTuning)} swapped the runtime out from
     * under them. The submitter saw "accepted" but the new worker pool
     * cannot see them; this counter makes that loss observable.
     *
     * @since 2.2.0
     */
    public static final String REASON_TUNING_SWAP = "tuning_swap";

    /**
     * Outcome labels used with {@link PrefetchMetrics#completed(String, String, String)}.
     * Re-exposed here so existing call sites and tests can stay on
     * {@code PrefetchCoordinator.OUTCOME_*}; {@link PrefetchMetrics} owns
     * the canonical values so the metric guards (e.g. {@code lastFetchAt})
     * cannot drift out of sync.
     */
    public static final String OUTCOME_FETCHED_200 = PrefetchMetrics.OUTCOME_FETCHED_200;
    public static final String OUTCOME_NEG_404 = PrefetchMetrics.OUTCOME_NEG_404;
    public static final String OUTCOME_COOLDOWN_BLOCKED = PrefetchMetrics.OUTCOME_COOLDOWN_BLOCKED;
    public static final String OUTCOME_UPSTREAM_5XX = PrefetchMetrics.OUTCOME_UPSTREAM_5XX;
    public static final String OUTCOME_TIMEOUT = PrefetchMetrics.OUTCOME_TIMEOUT;
    public static final String OUTCOME_ERROR = PrefetchMetrics.OUTCOME_ERROR;

    /**
     * Cooldown-evaluation timeout. A stuck cooldown service must not pin
     * worker threads or stall the in-flight set forever. On timeout we
     * fall through to "not blocked" so the upstream attempt happens; the
     * foreground request that triggered the prefetch will re-evaluate
     * cooldown on its own path.
     *
     * <p>TODO(Phase 6 audit): consider promoting to a settings key if
     * production tuning ever needs to vary this.</p>
     */
    public static final long COOLDOWN_TIMEOUT_SECONDS = 2L;

    /**
     * Upstream GET timeout. Bounds how long a single prefetch can hold a
     * semaphore permit. Hard-coded for now.
     *
     * <p>TODO(Phase 6 audit): consider promoting to a settings key if
     * production tuning ever needs to vary this.</p>
     */
    public static final long UPSTREAM_TIMEOUT_SECONDS = 30L;

    /**
     * Cooldown gate &mdash; abstracts the {@link com.auto1.pantera.cooldown.api.CooldownService#evaluate}
     * call so tests can stub it without standing up a database. Production
     * wiring builds a {@code CooldownRequest} from the task and calls
     * {@code CooldownService.evaluate(...).thenApply(CooldownResult::blocked)}.
     */
    @FunctionalInterface
    public interface CooldownGate {
        /**
         * @param task task being evaluated
         * @return {@code true} when the coordinate is currently blocked by
         *     cooldown and the prefetch must be skipped (and a negative cache
         *     entry written); {@code false} when the prefetch may proceed.
         */
        CompletableFuture<Boolean> isBlocked(PrefetchTask task);
    }

    /**
     * Upstream HTTP caller &mdash; abstracts the Jetty client send so tests
     * can stub a return code without standing up a real upstream. Production
     * wiring uses pantera's existing {@code JettyClientSlices} or a self-call
     * to the local proxy endpoint so the response is funnelled through the
     * normal cache-write pipeline.
     */
    @FunctionalInterface
    public interface UpstreamCaller {
        /**
         * Issue an upstream GET for {@code task.coord()}. The body is
         * irrelevant to this caller &mdash; the cache_write path on the
         * proxy side persists the bytes.
         *
         * @param task task being fetched
         * @return future resolving to the HTTP status code; failures should
         *     complete exceptionally so the coordinator can record
         *     {@code timeout}/{@code error} outcomes.
         */
        CompletableFuture<Integer> get(PrefetchTask task);
    }

    private final PrefetchMetrics metrics;
    private final PrefetchCircuitBreaker breaker;
    private final NegativeCache negativeCache;
    private final CooldownGate cooldown;
    private final UpstreamCaller upstream;
    private final Supplier<PrefetchTuning> tuningSupplier;
    /**
     * Cooldown timeout — overridable via the test seam constructor; defaults
     * to {@link #COOLDOWN_TIMEOUT_SECONDS}.
     */
    private final Duration cooldownTimeout;
    /**
     * Upstream timeout — overridable via the test seam constructor; defaults
     * to {@link #UPSTREAM_TIMEOUT_SECONDS}.
     */
    private final Duration upstreamTimeout;

    /**
     * In-flight dedup set. Key: {@code repoName + "|" + coordinate path}.
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Mutable runtime state &mdash; {@link AtomicReference} so that swaps
     * under {@link #applyTuning(PrefetchTuning)} are seen atomically by the
     * worker loop and by submitters.
     */
    private final AtomicReference<Runtime> runtime = new AtomicReference<>();

    /**
     * Build a coordinator with all collaborators injected. Call
     * {@link #start()} to spin up worker threads.
     *
     * @param metrics       Metrics sink.
     * @param breaker       Circuit breaker (open &rarr; reject all submits).
     * @param negativeCache Shared 404 cache.
     * @param cooldown      Cooldown gate.
     * @param upstream      Upstream HTTP caller.
     * @param tuningSupplier Live tuning supplier (read on every submit and
     *                      consulted for the swap when settings change).
     */
    public PrefetchCoordinator(
        final PrefetchMetrics metrics,
        final PrefetchCircuitBreaker breaker,
        final NegativeCache negativeCache,
        final CooldownGate cooldown,
        final UpstreamCaller upstream,
        final Supplier<PrefetchTuning> tuningSupplier
    ) {
        this(
            metrics, breaker, negativeCache, cooldown, upstream, tuningSupplier,
            Duration.ofSeconds(COOLDOWN_TIMEOUT_SECONDS),
            Duration.ofSeconds(UPSTREAM_TIMEOUT_SECONDS)
        );
    }

    /**
     * Test seam: override the cooldown / upstream timeouts. Production code
     * should use the 6-arg constructor; this overload is for unit tests
     * that need the timeouts to fire in less than a wall-clock second.
     *
     * @param metrics         Metrics sink.
     * @param breaker         Circuit breaker.
     * @param negativeCache   Shared 404 cache.
     * @param cooldown        Cooldown gate.
     * @param upstream        Upstream HTTP caller.
     * @param tuningSupplier  Tuning supplier.
     * @param cooldownTimeout Cooldown evaluation timeout.
     * @param upstreamTimeout Upstream call timeout.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    PrefetchCoordinator(
        final PrefetchMetrics metrics,
        final PrefetchCircuitBreaker breaker,
        final NegativeCache negativeCache,
        final CooldownGate cooldown,
        final UpstreamCaller upstream,
        final Supplier<PrefetchTuning> tuningSupplier,
        final Duration cooldownTimeout,
        final Duration upstreamTimeout
    ) {
        this.metrics = metrics;
        this.breaker = breaker;
        this.negativeCache = negativeCache;
        this.cooldown = cooldown;
        this.upstream = upstream;
        this.tuningSupplier = tuningSupplier;
        this.cooldownTimeout = cooldownTimeout;
        this.upstreamTimeout = upstreamTimeout;
        this.runtime.set(buildRuntime(tuningSupplier.get()));
    }

    /**
     * Start the worker pool. Idempotent: subsequent calls are no-ops.
     */
    public void start() {
        final Runtime curr = this.runtime.get();
        if (curr.started.compareAndSet(false, true)) {
            startWorkers(curr);
        }
    }

    /**
     * Submit a task for asynchronous prefetch. The call returns immediately;
     * the result &mdash; including drops &mdash; is reported through
     * {@link PrefetchMetrics}.
     *
     * @param task Task to dispatch.
     */
    public void submit(final PrefetchTask task) {
        if (this.breaker.isOpen()) {
            this.metrics.dropped(task.repoName(), REASON_CIRCUIT_OPEN);
            return;
        }
        final String key = dedupKey(task);
        if (!this.inFlight.add(key)) {
            this.metrics.dropped(task.repoName(), REASON_DEDUP);
            return;
        }
        final Runtime curr = this.runtime.get();
        if (!curr.queue.offer(task)) {
            this.inFlight.remove(key);
            this.metrics.dropped(task.repoName(), REASON_QUEUE_FULL);
            this.breaker.recordDrop();
            return;
        }
        this.metrics.dispatched(task.repoName(), ecosystemKey(task.coord()));
    }

    /**
     * Apply a new tuning snapshot. Swaps queue + semaphores atomically and
     * (if {@code workerThreads} changed) replaces the worker pool. Tasks
     * sitting in the previous queue are drained and dropped &mdash; they
     * would otherwise be invisible to the new worker pool. Each drained
     * task is counted against {@link #REASON_TUNING_SWAP} per repo so the
     * loss is visible to operators on the {@code pantera_prefetch_dropped_total}
     * counter.
     *
     * <p><b>Supplier contract.</b> Callers should not pass a {@code next}
     * snapshot that diverges from the {@link Supplier Supplier&lt;PrefetchTuning&gt;}
     * passed to the constructor. The settings-listener path
     * ({@link #subscribe(RuntimeSettingsCache)}) calls {@code applyTuning(supplier.get())}
     * — every caller should follow the same shape so a follow-up listener
     * fire cannot silently overwrite a manual swap. Direct calls with a
     * one-off snapshot are supported but will be replaced on the next
     * {@code prefetch.*} settings change.</p>
     *
     * @param next Updated tuning snapshot. Must come from the same supplier
     *             that was passed to the constructor (or be temporarily
     *             replacing it for a controlled test).
     */
    public synchronized void applyTuning(final PrefetchTuning next) {
        final Runtime prev = this.runtime.get();
        if (prev.tuning.equals(next)) {
            return;
        }
        final Runtime fresh = buildRuntime(next);
        // Drop pending old-queue items. We cannot move them into the new
        // queue safely since semaphore/concurrency assumptions changed; the
        // submitter saw "accepted" but the dispatcher will silently lose
        // them. Count each drained task by repo so the loss surfaces on
        // pantera_prefetch_dropped_total{reason="tuning_swap"}.
        final java.util.List<PrefetchTask> drained = new java.util.ArrayList<>();
        prev.queue.drainTo(drained);
        for (final PrefetchTask drainedTask : drained) {
            this.inFlight.remove(dedupKey(drainedTask));
            this.metrics.dropped(drainedTask.repoName(), REASON_TUNING_SWAP);
        }
        // Stop old workers; new pool starts only if previous was running.
        prev.shutdown();
        this.runtime.set(fresh);
        if (prev.started.get()) {
            fresh.started.set(true);
            startWorkers(fresh);
        }
        EcsLogger.info("com.auto1.pantera.prefetch.PrefetchCoordinator")
            .message("Prefetch tuning swapped")
            .field("global_concurrency", next.globalConcurrency())
            .field("per_upstream_concurrency", next.perUpstreamConcurrency())
            .field("queue_capacity", next.queueCapacity())
            .field("worker_threads", next.workerThreads())
            .field("drained_tasks", drained.size())
            .eventCategory("configuration")
            .eventAction("prefetch_tune")
            .log();
    }

    /**
     * Subscribe the coordinator to {@code prefetch.} settings changes so it
     * reconfigures itself live.
     *
     * @param cache RuntimeSettingsCache to subscribe on.
     */
    public void subscribe(final RuntimeSettingsCache cache) {
        cache.addListener("prefetch.", key -> applyTuning(this.tuningSupplier.get()));
    }

    /**
     * Stop the worker pool. Pending tasks are dropped.
     */
    public void stop() {
        final Runtime curr = this.runtime.get();
        curr.shutdown();
    }

    /**
     * Visible for tests &mdash; reports queue depth.
     *
     * @return current queue size
     */
    public int queueDepth() {
        return this.runtime.get().queue.size();
    }

    /**
     * Visible for tests &mdash; live in-flight count.
     *
     * @return in-flight set size
     */
    public int inFlightCount() {
        return this.inFlight.size();
    }

    // ============================================================
    //  Internals
    // ============================================================

    private Runtime buildRuntime(final PrefetchTuning tuning) {
        return new Runtime(
            tuning,
            new ArrayBlockingQueue<>(tuning.queueCapacity()),
            new Semaphore(tuning.globalConcurrency()),
            new ConcurrentHashMap<>()
        );
    }

    private void startWorkers(final Runtime curr) {
        for (int idx = 0; idx < curr.tuning.workerThreads(); idx += 1) {
            curr.workers.submit(() -> workerLoop(curr));
        }
    }

    private void workerLoop(final Runtime curr) {
        while (!Thread.currentThread().isInterrupted()) {
            final PrefetchTask task;
            try {
                task = curr.queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                if (curr.stopping.get()) {
                    return;
                }
                continue;
            }
            try {
                process(task, curr);
            } catch (final Throwable err) {
                this.inFlight.remove(dedupKey(task));
                this.metrics.completed(
                    task.repoName(),
                    ecosystemKey(task.coord()),
                    OUTCOME_ERROR
                );
                EcsLogger.warn("com.auto1.pantera.prefetch.PrefetchCoordinator")
                    .message("Prefetch worker caught throwable")
                    .field("error.message", err.getMessage())
                    .log();
            }
        }
    }

    private void process(final PrefetchTask task, final Runtime curr) {
        final String key = dedupKey(task);
        final String ecosystem = ecosystemKey(task.coord());
        // 1) Negative cache short-circuit.
        final NegativeCacheKey nck = negKey(task);
        if (this.negativeCache.isKnown404(nck)) {
            this.inFlight.remove(key);
            this.metrics.completed(task.repoName(), ecosystem, OUTCOME_NEG_404);
            return;
        }
        // 2) Cooldown — bounded so a stuck cooldown service can't pin the
        // worker thread or leak the in-flight slot. On timeout we treat
        // the coordinate as "not blocked" and let the upstream attempt
        // happen; the foreground request that triggered this prefetch
        // re-evaluates cooldown on its own path.
        final CompletableFuture<Boolean> cooldownFuture = this.cooldown.isBlocked(task)
            .orTimeout(this.cooldownTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.prefetch.PrefetchCoordinator")
                    .message("Cooldown check timed out / failed: " + err.getMessage())
                    .field("repository.name", task.repoName())
                    .log();
                return false;
            });
        cooldownFuture.whenComplete((blocked, cdErr) -> {
            if (cdErr != null) {
                this.inFlight.remove(key);
                this.metrics.completed(task.repoName(), ecosystem, OUTCOME_ERROR);
                return;
            }
            if (Boolean.TRUE.equals(blocked)) {
                this.negativeCache.cacheNotFound(nck);
                this.inFlight.remove(key);
                this.metrics.completed(task.repoName(), ecosystem, OUTCOME_COOLDOWN_BLOCKED);
                return;
            }
            // 3) Acquire semaphores: per-host first, then global.
            // The per-host cap is ecosystem-specific (npm=4, maven/gradle=16
            // by default) so the cap is resolved at semaphore-creation time.
            // Two upstreams of different ecosystems get different caps;
            // two upstreams of the same ecosystem share the same cap value
            // but distinct semaphore instances.
            final String host = upstreamHost(task);
            final int perHostCap = curr.tuning.perUpstreamFor(ecosystem);
            final Semaphore perHost = curr.perUpstream.computeIfAbsent(
                host, h -> new Semaphore(perHostCap)
            );
            if (!perHost.tryAcquire()) {
                this.inFlight.remove(key);
                this.metrics.dropped(task.repoName(), REASON_SEMAPHORE);
                this.breaker.recordDrop();
                return;
            }
            if (!curr.global.tryAcquire()) {
                perHost.release();
                this.inFlight.remove(key);
                this.metrics.dropped(task.repoName(), REASON_SEMAPHORE);
                this.breaker.recordDrop();
                return;
            }
            // 4) Fire upstream.
            fireUpstream(task, curr, ecosystem, key, nck, perHost);
        });
    }

    /**
     * Resolve the host key used for per-upstream throttling. Falls back
     * to the repo name if {@code task.upstreamUrl()} is malformed or has
     * no host (so a misconfigured upstream still gets bounded, and
     * tests that pass through {@code "https://repo1.maven.org/maven2"}
     * still bucket by host).
     */
    private static String upstreamHost(final PrefetchTask task) {
        final String url = task.upstreamUrl();
        if (url != null && !url.isEmpty()) {
            try {
                final String host = URI.create(url).getHost();
                if (host != null && !host.isEmpty()) {
                    return host;
                }
            } catch (final IllegalArgumentException ex) {
                // fall through to repo-name fallback
            }
        }
        return task.repoName();
    }

    private void fireUpstream(
        final PrefetchTask task,
        final Runtime curr,
        final String ecosystem,
        final String key,
        final NegativeCacheKey nck,
        final Semaphore perHost
    ) {
        final PrefetchMetrics.InflightHandle handle = this.metrics.inflight(task.repoName());
        handle.inc();
        final Instant started = Instant.now();
        final CompletableFuture<Integer> upstreamFuture = this.upstream.get(task)
            .orTimeout(this.upstreamTimeout.toMillis(), TimeUnit.MILLISECONDS);
        upstreamFuture.whenComplete((status, err) -> {
            try {
                if (err != null) {
                    final Throwable cause = err instanceof CompletionException
                        ? err.getCause() : err;
                    final String outcome = (cause instanceof TimeoutException)
                        ? OUTCOME_TIMEOUT : OUTCOME_UPSTREAM_5XX;
                    this.metrics.completed(task.repoName(), ecosystem, outcome);
                } else if (status == null) {
                    this.metrics.completed(task.repoName(), ecosystem, OUTCOME_ERROR);
                } else if (status == 200) {
                    this.metrics.completed(task.repoName(), ecosystem, OUTCOME_FETCHED_200);
                } else if (status == 404) {
                    this.negativeCache.cacheNotFound(nck);
                    this.metrics.completed(task.repoName(), ecosystem, OUTCOME_NEG_404);
                } else if (status >= 500) {
                    this.metrics.completed(task.repoName(), ecosystem, OUTCOME_UPSTREAM_5XX);
                } else {
                    this.metrics.completed(task.repoName(), ecosystem, OUTCOME_ERROR);
                }
                this.metrics.recordFetch(task.repoName(), Duration.between(started, Instant.now()));
            } finally {
                curr.global.release();
                perHost.release();
                handle.dec();
                this.inFlight.remove(key);
            }
        });
    }

    private static String dedupKey(final PrefetchTask task) {
        return task.repoName() + '|' + task.coord().path();
    }

    /**
     * Resolve the ecosystem key used for per-upstream concurrency lookup
     * and metric labels. Phase 13 packument coords share the same upstream
     * registry as tarball coords (registry.npmjs.org), so they MUST bucket
     * under the same per-host cap as NPM tarballs ({@code "npm"} = 4 by
     * default) — otherwise packument prefetches would silently fall back
     * to the global default (16) and oversaturate the upstream pool.
     */
    private static String ecosystemKey(final Coordinate coord) {
        return switch (coord.ecosystem()) {
            case NPM, NPM_PACKUMENT -> "npm";
            case MAVEN -> "maven";
        };
    }

    private static NegativeCacheKey negKey(final PrefetchTask task) {
        final Coordinate coord = task.coord();
        final String name;
        switch (coord.ecosystem()) {
            case MAVEN:
                name = coord.groupOrNamespace() + '/' + coord.name();
                break;
            case NPM:
            case NPM_PACKUMENT:
            default:
                name = coord.groupOrNamespace().isEmpty()
                    ? coord.name() : coord.groupOrNamespace() + '/' + coord.name();
                break;
        }
        return new NegativeCacheKey(
            task.repoName(),
            task.repoType(),
            name,
            coord.version()
        );
    }

    /**
     * Snapshot of all mutable runtime state. Replaced wholesale on
     * tuning swap so worker loops always see a consistent triple of
     * {queue, global, perUpstream}.
     */
    private static final class Runtime {
        final PrefetchTuning tuning;
        final BlockingQueue<PrefetchTask> queue;
        final Semaphore global;
        /**
         * Per-host semaphore map (spec §5). Keyed by upstream URL host;
         * each value caps concurrent in-flight prefetches against that
         * host at the ecosystem-specific cap resolved via
         * {@link PrefetchTuning#perUpstreamFor(String)} at first-sight
         * of the host. The map is populated lazily via
         * {@code computeIfAbsent}.
         */
        final ConcurrentMap<String, Semaphore> perUpstream;
        final ExecutorService workers;
        final java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean stopping =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        Runtime(
            final PrefetchTuning tuning,
            final BlockingQueue<PrefetchTask> queue,
            final Semaphore global,
            final ConcurrentMap<String, Semaphore> perUpstream
        ) {
            this.tuning = tuning;
            this.queue = queue;
            this.global = global;
            this.perUpstream = perUpstream;
            this.workers = Executors.newFixedThreadPool(
                Math.max(1, tuning.workerThreads()),
                new NamedThreadFactory()
            );
        }

        void shutdown() {
            this.stopping.set(true);
            this.workers.shutdownNow();
            try {
                this.workers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private static final AtomicInteger SEQ = new AtomicInteger(0);

        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(
                runnable,
                "pantera-prefetch-" + SEQ.getAndIncrement()
            );
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * Convenience adapter: build an {@link UpstreamCaller} from a
     * {@code Function<PrefetchTask, CompletableFuture<Integer>>}. Useful
     * for production wiring where the caller closes over a
     * {@code Supplier<HttpClient>} (Jetty 12) to issue the GET.
     *
     * @param fn HTTP caller function.
     * @return UpstreamCaller wrapping the function.
     */
    public static UpstreamCaller upstreamFromFunction(
        final Function<PrefetchTask, CompletableFuture<Integer>> fn
    ) {
        return fn::apply;
    }
}
