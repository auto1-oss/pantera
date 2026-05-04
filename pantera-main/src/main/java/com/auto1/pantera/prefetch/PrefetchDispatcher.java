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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Dispatcher hook called by {@code ProxyCacheWriter} immediately after a
 * successful cache write. Decides whether the freshly cached bytes should
 * trigger a transitive prefetch and, if so, fans the parsed coordinates
 * out to the {@link PrefetchCoordinator}.
 *
 * <p>Decision flow (every step is short-circuiting and side-effect-free
 * before the {@code submit} call):</p>
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
 *   <li>Parse the cached file. The parser contract guarantees an empty
 *       list (not a throw) on malformed input, but a misbehaving parser
 *       could still raise; we wrap the whole call in {@code try / catch
 *       Throwable}.</li>
 *   <li>Submit one {@link PrefetchTask} per coordinate to the
 *       coordinator's submit sink.</li>
 * </ol>
 *
 * <p><b>Failure containment.</b> The dispatcher catches every
 * {@link Throwable} from steps 5–6. The cache-write callback path
 * (Task 11) MUST NEVER fail because of dispatcher logic; an exception
 * here would otherwise propagate into the proxy response thread and
 * potentially mask a successful cache write. On a swallowed throwable
 * we emit an ECS WARN with the repo + url path + error message and move
 * on.</p>
 *
 * <p><b>Lookup signature decision.</b> {@code RepoSettings} does not yet
 * expose a {@code prefetchEnabled()} accessor — that's added in Task 20.
 * To keep Task 18 decoupled from the still-pending API we take simple
 * {@code Function<String, ?>} lookups: one for the per-repo flag, one
 * for the upstream URL, one for the repo type. Task 19 / Task 20 wire
 * these to whatever the eventual {@code RepoSettings} surface exposes.
 * </p>
 *
 * <p>The submit sink is taken as a {@link Consumer Consumer&lt;PrefetchTask&gt;}
 * (typically {@code coordinator::submit}). This keeps the dispatcher
 * trivially testable without bringing the full coordinator stack into
 * unit tests.</p>
 *
 * @since 2.2.0
 */
public final class PrefetchDispatcher {

    /**
     * Logger name used for all dispatcher events.
     */
    private static final String LOGGER_NAME = "com.auto1.pantera.prefetch.PrefetchDispatcher";

    private final Supplier<PrefetchTuning> tuningSupplier;
    private final Function<String, Boolean> repoPrefetchEnabled;
    private final Function<String, String> upstreamUrlLookup;
    private final Map<String, PrefetchParser> parsersByType;
    private final Function<String, String> repoTypeLookup;
    private final Consumer<PrefetchTask> submitSink;

    /**
     * Build a dispatcher with all collaborators injected.
     *
     * @param tuningSupplier      Live tuning supplier (read on every
     *                            event; honours {@code prefetch.enabled}
     *                            kill-switch flips immediately).
     * @param repoPrefetchEnabled Lookup of the per-repo prefetch flag
     *                            keyed by repo name. Returning
     *                            {@code Boolean.FALSE} or {@code null}
     *                            disables prefetch for the repo.
     * @param upstreamUrlLookup   Repo-name → upstream URL lookup. The
     *                            returned URL is stamped on every
     *                            generated {@link PrefetchTask} so the
     *                            coordinator can route the GET through
     *                            the right host (per-host semaphores).
     * @param parsersByType       Parser registry keyed by repo type
     *                            (e.g. {@code "maven-proxy"},
     *                            {@code "npm-proxy"}). Missing entries
     *                            are no-ops.
     * @param repoTypeLookup      Repo-name → repo type lookup. Returning
     *                            {@code null} disables prefetch for the
     *                            repo (parser registry can't be
     *                            consulted without a type key).
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
        this.tuningSupplier = tuningSupplier;
        this.repoPrefetchEnabled = repoPrefetchEnabled;
        this.upstreamUrlLookup = upstreamUrlLookup;
        this.parsersByType = Map.copyOf(parsersByType);
        this.repoTypeLookup = repoTypeLookup;
        this.submitSink = submitSink;
    }

    /**
     * Hook entry point — called by {@code ProxyCacheWriter} after every
     * successful cache write. Never throws; every non-trivial step is
     * guarded.
     *
     * @param event Cache write event carrying repo name, url path, and
     *              the bytes-on-disk hint.
     */
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
            // 5) Parse and 6) submit.
            dispatch(event, repoType, parser);
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

    private void dispatch(
        final CacheWriteEvent event,
        final String repoType,
        final PrefetchParser parser
    ) {
        final Path bytes = event.bytesOnDisk();
        final List<Coordinate> coords = parser.parse(bytes);
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
    }
}
