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

import com.auto1.pantera.http.cache.CacheWriteEvent;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.prefetch.parser.PrefetchParser;
import com.auto1.pantera.settings.runtime.PrefetchTuning;
import io.vertx.core.MultiMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Dispatcher hook called by {@code ProxyCacheWriter} (and the
 * {@code NpmCacheWriteBridge}) immediately after a successful cache
 * write. Decides whether the freshly cached bytes should trigger a
 * transitive prefetch and, if so, fans the parsed coordinates out to
 * the {@link PrefetchCoordinator}.
 *
 * <h2>Decision flow</h2>
 * <p>Every step is short-circuiting and side-effect-free before the
 * {@code submit} call:</p>
 * <ol>
 *   <li>Global kill-switch — {@code prefetch.enabled} from the runtime
 *       settings supplier. If {@code false}, return immediately. The
 *       parser is never invoked.</li>
 *   <li>Per-repo flag — {@code repoPrefetchEnabled.apply(repoName)}. If
 *       {@code Boolean.FALSE} or {@code null}, return.</li>
 *   <li>Repo-type lookup — {@code repoTypeLookup.apply(repoName)}. If the
 *       type cannot be resolved (returns {@code null}), return.</li>
 *   <li>Parser registry — {@code parsersByType.get(repoType)}. If no
 *       parser is registered for the type (e.g. {@code file-proxy}),
 *       return without error.</li>
 *   <li>Synchronously snapshot the cached bytes to a dispatcher-owned
 *       temp file (the source temp file is deleted by the cache writer
 *       as soon as the callback returns).</li>
 *   <li>Hand the snapshot off to the dispatch executor — the cache-write
 *       callback returns in microseconds. The executor runs the parse +
 *       per-dep packument lookup + coordinator submit asynchronously and
 *       deletes the snapshot when done.</li>
 * </ol>
 *
 * <h2>Why an executor</h2>
 * <p>For npm, parsing a tarball means gunzip + tar extract + JSON parse
 * + per-dependency packument disk-read (1-5MB each) + semver walk. Done
 * synchronously on the cache-write thread (which itself runs on the
 * response thread that completed the proxy save), an {@code npm install}
 * with ~280 cache-miss writes adds 4-5s of CPU+disk to the wall. Maven
 * is lighter (XMLStream parser) but still synchronous. This class
 * therefore enqueues all post-decision work to a small bounded
 * {@link ThreadPoolExecutor} (core 2, max 4, queue 1024). Hot-path
 * cost is now: parse decision (O(1) Map lookups) + a single
 * {@link Files#copy} of the freshly-cached bytes + an
 * {@link ExecutorService#execute} call.</p>
 *
 * <h2>Failure containment</h2>
 * <p>The dispatcher catches every {@link Throwable} on both the hot path
 * and the async dispatch path. The cache-write callback (Task 11) MUST
 * NEVER fail because of dispatcher logic; an exception here would
 * otherwise propagate into the proxy response thread and potentially
 * mask a successful cache write. On the hot path, exhaustion of the
 * dispatch queue increments {@link #droppedEvents} and emits a single
 * WARN; the swallow is on purpose.</p>
 *
 * <h2>Lookup signature decision</h2>
 * <p>{@code RepoSettings} does not expose a {@code prefetchEnabled()}
 * accessor; we take simple {@code Function<String, ?>} lookups instead.
 * Production wiring lambdas onto {@code slices::prefetchEnabledFor} etc.
 * The submit sink is taken as a {@link Consumer Consumer&lt;PrefetchTask&gt;}
 * (typically {@code coordinator::submit}). This keeps the dispatcher
 * trivially testable without bringing the full coordinator stack into
 * unit tests.</p>
 *
 * @since 2.2.0
 */
public final class PrefetchDispatcher {

    /** Logger name used for all dispatcher events. */
    private static final String LOGGER_NAME = "com.auto1.pantera.prefetch.PrefetchDispatcher";

    /** Default core thread count for the dispatch executor. */
    private static final int DEFAULT_CORE_THREADS = 2;

    /** Default max thread count for the dispatch executor. */
    private static final int DEFAULT_MAX_THREADS = 4;

    /** Default bounded queue capacity for the dispatch executor. */
    private static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** Default keep-alive (seconds) for non-core threads. */
    private static final long DEFAULT_KEEPALIVE_SECONDS = 60L;

    /** Wait window for graceful shutdown of the dispatch executor. */
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    /** Counter feeding the dispatch worker thread names. */
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    private final Supplier<PrefetchTuning> tuningSupplier;
    private final Function<String, Boolean> repoPrefetchEnabled;
    private final Function<String, String> upstreamUrlLookup;
    private final Map<String, PrefetchParser> parsersByType;
    private final Function<String, String> repoTypeLookup;
    private final Consumer<PrefetchTask> submitSink;

    /** Bounded dispatch executor. Owned by this dispatcher. */
    private final ExecutorService dispatchExecutor;

    /** Counter for dropped events (queue full). Surfaced via WARN log. */
    private final AtomicLong droppedEvents = new AtomicLong();

    /**
     * Production constructor — builds a default bounded
     * {@link ThreadPoolExecutor} (core 2, max 4, queue 1024).
     *
     * @param tuningSupplier      Live tuning supplier (read on every
     *                            event; honours {@code prefetch.enabled}
     *                            kill-switch flips immediately).
     * @param repoPrefetchEnabled Lookup of the per-repo prefetch flag
     *                            keyed by repo name. Returning
     *                            {@code Boolean.FALSE} or {@code null}
     *                            disables prefetch for the repo.
     * @param upstreamUrlLookup   Repo-name -&gt; upstream URL lookup.
     * @param parsersByType       Parser registry keyed by repo type.
     * @param repoTypeLookup      Repo-name -&gt; repo type lookup.
     * @param submitSink          Sink that accepts each generated
     *                            {@link PrefetchTask}. Production
     *                            wiring uses {@code coordinator::submit}.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public PrefetchDispatcher(
        final Supplier<PrefetchTuning> tuningSupplier,
        final Function<String, Boolean> repoPrefetchEnabled,
        final Function<String, String> upstreamUrlLookup,
        final Map<String, PrefetchParser> parsersByType,
        final Function<String, String> repoTypeLookup,
        final Consumer<PrefetchTask> submitSink
    ) {
        this(
            tuningSupplier, repoPrefetchEnabled, upstreamUrlLookup,
            parsersByType, repoTypeLookup, submitSink,
            defaultDispatchExecutor()
        );
    }

    /**
     * Test-friendly constructor — accepts an injected executor so unit
     * tests can use a same-thread or tightly-bounded executor to drive
     * queue-full / drop-counter scenarios deterministically.
     *
     * @param tuningSupplier      Live tuning supplier.
     * @param repoPrefetchEnabled Per-repo flag lookup.
     * @param upstreamUrlLookup   Per-repo upstream URL lookup.
     * @param parsersByType       Parser registry.
     * @param repoTypeLookup      Per-repo type lookup.
     * @param submitSink          Submit sink.
     * @param dispatchExecutor    Executor used to run async dispatch.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public PrefetchDispatcher(
        final Supplier<PrefetchTuning> tuningSupplier,
        final Function<String, Boolean> repoPrefetchEnabled,
        final Function<String, String> upstreamUrlLookup,
        final Map<String, PrefetchParser> parsersByType,
        final Function<String, String> repoTypeLookup,
        final Consumer<PrefetchTask> submitSink,
        final ExecutorService dispatchExecutor
    ) {
        this.tuningSupplier = tuningSupplier;
        this.repoPrefetchEnabled = repoPrefetchEnabled;
        this.upstreamUrlLookup = upstreamUrlLookup;
        this.parsersByType = Map.copyOf(parsersByType);
        this.repoTypeLookup = repoTypeLookup;
        this.submitSink = submitSink;
        this.dispatchExecutor = dispatchExecutor;
    }

    /** Default bounded executor for production use. */
    private static ThreadPoolExecutor defaultDispatchExecutor() {
        final ThreadFactory tf = r -> {
            final Thread t = new Thread(r,
                "pantera-prefetch-dispatch-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        // AbortPolicy throws on full → we count the drop and warn.
        return new ThreadPoolExecutor(
            DEFAULT_CORE_THREADS, DEFAULT_MAX_THREADS,
            DEFAULT_KEEPALIVE_SECONDS, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY),
            tf,
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Hook entry point — called by {@code ProxyCacheWriter} after every
     * successful cache write. Never throws; every non-trivial step is
     * guarded.
     *
     * <p>Hot-path work: O(1) parser-decision lookups + a single
     * {@link Files#copy} of the freshly-cached bytes to a dispatcher-
     * owned snapshot + an {@link ExecutorService#execute} call. Parse +
     * per-dep packument lookup + submit run on the dispatch executor.</p>
     *
     * @param event Cache write event carrying repo name, url path, and
     *              the bytes-on-disk hint.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void onCacheWrite(final CacheWriteEvent event) {
        try {
            // 1) Global kill-switch.
            final PrefetchTuning tuning = this.tuningSupplier.get();
            if (tuning == null || !tuning.enabled()) {
                return;
            }
            // 2) Per-repo flag.
            final Boolean repoFlag = this.repoPrefetchEnabled.apply(event.repoName());
            if (!Boolean.TRUE.equals(repoFlag)) {
                return;
            }
            // 3) Repo-type lookup.
            final String repoType = this.repoTypeLookup.apply(event.repoName());
            if (repoType == null) {
                return;
            }
            // 4) Parser registry.
            final PrefetchParser parser = this.parsersByType.get(repoType);
            if (parser == null) {
                return;
            }
            // 5) Snapshot the cached bytes synchronously — the source
            // temp file is deleted as soon as this callback returns
            // (see CacheWriteEvent contract). The async stage owns and
            // deletes the snapshot.
            final Path snapshot;
            try {
                snapshot = snapshotBytes(event.bytesOnDisk());
            } catch (final IOException ioe) {
                EcsLogger.warn(LOGGER_NAME)
                    .message("Failed to snapshot cached bytes for async dispatch")
                    .field("repository.name", event.repoName())
                    .field("url.path", event.urlPath())
                    .field("error.message", String.valueOf(ioe.getMessage()))
                    .eventCategory("process")
                    .eventAction("prefetch_dispatch")
                    .log();
                return;
            }
            // 6) Hand off to the dispatch executor.
            try {
                this.dispatchExecutor.execute(
                    () -> dispatchAsync(event, repoType, parser, snapshot)
                );
            } catch (final RejectedExecutionException rex) {
                this.droppedEvents.incrementAndGet();
                deleteQuietly(snapshot);
                EcsLogger.warn(LOGGER_NAME)
                    .message("Prefetch dispatch queue full; dropping event")
                    .field("repository.name", event.repoName())
                    .field("url.path", event.urlPath())
                    .field("prefetch.dropped_total", this.droppedEvents.get())
                    .eventCategory("process")
                    .eventAction("prefetch_dispatch_drop")
                    .log();
            }
        } catch (final Throwable err) {
            // Hard contract: cache-write callback must never fail. Log and swallow.
            EcsLogger.warn(LOGGER_NAME)
                .message("PrefetchDispatcher swallowed throwable from cache-write callback")
                .field("repository.name", event.repoName())
                .field("url.path", event.urlPath())
                .field("error.message", String.valueOf(err.getMessage()))
                .eventCategory("process")
                .eventAction("prefetch_dispatch")
                .log();
        }
    }

    /**
     * Async dispatch body — runs on the dispatch executor. Owns the
     * snapshot file lifetime: parses, submits, deletes.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void dispatchAsync(
        final CacheWriteEvent event,
        final String repoType,
        final PrefetchParser parser,
        final Path snapshot
    ) {
        try {
            final List<Coordinate> coords = parser.parse(snapshot);
            if (coords == null || coords.isEmpty()) {
                return;
            }
            final String upstream = this.upstreamUrlLookup.apply(event.repoName());
            final Instant now = Instant.now();
            final MultiMap headers = MultiMap.caseInsensitiveMultiMap();
            for (final Coordinate coord : coords) {
                final PrefetchTask task = new PrefetchTask(
                    event.repoName(),
                    repoType,
                    upstream,
                    coord,
                    headers,
                    now
                );
                this.submitSink.accept(task);
            }
        } catch (final Throwable err) {
            EcsLogger.warn(LOGGER_NAME)
                .message("PrefetchDispatcher async dispatch swallowed throwable")
                .field("repository.name", event.repoName())
                .field("url.path", event.urlPath())
                .field("error.message", String.valueOf(err.getMessage()))
                .eventCategory("process")
                .eventAction("prefetch_dispatch")
                .log();
        } finally {
            deleteQuietly(snapshot);
        }
    }

    /**
     * Copy the cache-write source file to a dispatcher-owned snapshot.
     */
    private static Path snapshotBytes(final Path source) throws IOException {
        final Path tmp = Files.createTempFile("pantera-prefetch-dispatch-", ".bin");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ioe) {
            deleteQuietly(tmp);
            throw ioe;
        }
        return tmp;
    }

    /** Best-effort temp-file delete; silent on failure. */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private static void deleteQuietly(final Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (final Exception ignored) {
            // best-effort
        }
    }

    /**
     * Number of events dropped because the dispatch queue was full.
     * Exposed for tests + admin diagnostics.
     *
     * @return Cumulative drop count.
     */
    public long droppedEventsTotal() {
        return this.droppedEvents.get();
    }

    /**
     * Drain in-flight async dispatches and shut the executor down.
     * Wait up to {@value #SHUTDOWN_WAIT_SECONDS}s for graceful drain
     * before forcing a shutdown.
     */
    public void stop() {
        this.dispatchExecutor.shutdown();
        try {
            if (!this.dispatchExecutor.awaitTermination(
                SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS
            )) {
                this.dispatchExecutor.shutdownNow();
            }
        } catch (final InterruptedException ie) {
            this.dispatchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
