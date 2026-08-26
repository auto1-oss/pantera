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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.http.CacheTimeControl;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.KeyFromPath;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-through, TTL-cached, single-flighted loader for the Go proxy
 * "resolution surfaces" — the {@code @v/list} and {@code @latest} base
 * documents.
 *
 * <p>Before this class existed, {@code CachedProxySlice} intercepted both
 * endpoints ahead of its cache-first pipeline and fetched them live from
 * upstream on every request (see {@code CachedProxySlice.java:265-290}
 * pre-WS4-go.2), so a cold upstream instantly broke {@code go get} /
 * {@code go list -m -versions} even for a module whose {@code .zip} was
 * already cached. This loader closes that gap: the raw, unfiltered
 * upstream body is cached through {@link Cache} with a revived
 * {@link CacheTimeControl} (12h TTL), concurrent misses for the same
 * document collapse onto one upstream call via {@link SingleFlight}, and
 * an upstream failure falls back to serving whatever is on disk — stale,
 * but better than a hard failure — as long as some copy exists.</p>
 *
 * <p>Callers (the cooldown handlers) run per-request cooldown filtering
 * over the returned base bytes; this class deliberately caches the
 * <em>unfiltered</em> document so a cooldown lift/re-block is reflected
 * on the very next request without forcing a re-fetch.</p>
 *
 * @since 2.3.0
 */
public final class GoMetadataBaseLoader {

    /**
     * Body used when the upstream call itself failed before any HTTP
     * status was available (connection refused, timeout, ...) and no
     * cached copy exists to fall back to.
     */
    private static final byte[] UPSTREAM_UNAVAILABLE_BODY =
        "Upstream temporarily unavailable".getBytes(StandardCharsets.UTF_8);

    /**
     * Upstream slice shared with the main Go proxy.
     */
    private final Slice upstream;

    /**
     * Storage-backed cache the base documents are read through.
     */
    private final Cache cache;

    /**
     * Backing storage, when this repository has one. Used both to build
     * the revived {@link CacheTimeControl} and to read a stale fallback
     * directly when {@link Cache#load} comes back empty after an
     * upstream failure. Empty for ephemeral / cache-less wiring, in
     * which case the base document is fetched live on every call —
     * matches the pre-existing fallback for artifact caching in
     * {@code CachedProxySlice}.
     */
    private final Optional<Storage> storage;

    /**
     * Per-document single-flight gate. Concurrent callers for the same
     * cold {@code @v/list} / {@code @latest} key collapse onto a single
     * upstream call; followers park then receive the leader's result.
     */
    private final SingleFlight<Key, Outcome> singleFlight;

    /**
     * Repository name, for logging only.
     */
    private final String repoName;

    /**
     * New loader.
     *
     * @param upstream Upstream Go module proxy slice
     * @param cache Storage-backed cache for the base documents
     * @param storage Optional backing storage (TTL + stale fallback)
     * @param repoName Repository name (logging only)
     */
    public GoMetadataBaseLoader(
        final Slice upstream,
        final Cache cache,
        final Optional<Storage> storage,
        final String repoName
    ) {
        this.upstream = upstream;
        this.cache = cache;
        this.storage = storage;
        this.repoName = repoName;
        this.singleFlight = new SingleFlight<>(
            Duration.ofMinutes(5),
            10_000,
            ContextualExecutor.contextualize(ForkJoinPool.commonPool())
        );
    }

    /**
     * Resolve the base document at {@code path}, single-flighted per
     * storage key.
     *
     * @param path Request path, e.g. {@code /module/@v/list}
     * @param module Module name (logging only)
     * @return Future outcome — available (fresh, cache-hit, or stale
     *         fallback) or unavailable (nothing cached and upstream
     *         failed / returned non-2xx)
     */
    public CompletableFuture<Outcome> load(final String path, final String module) {
        final Key key = new KeyFromPath(path);
        return this.singleFlight.load(key, () -> this.fetch(path, module, key));
    }

    /**
     * Loader body invoked at most once per single-flight burst.
     *
     * <p>{@link Cache#load} builds its {@code switchIfEmpty} upstream-fetch
     * branch as a plain Java method argument — {@code remote.get()} is
     * therefore evaluated eagerly on <em>every</em> call, regardless of
     * whether the earlier cache-hit branch of the chain ends up being used
     * ({@code FromStorageCache.java:switchIfEmpty(RxFuture.single(remote.get())...)}).
     * A {@code Remote} whose {@code get()} performs real I/O would fire an
     * upstream call on every cache hit if passed directly. {@code
     * CachedProxySlice} already works around this by pre-checking with
     * {@link Remote#EMPTY} (a no-op, side-effect-free supplier) before ever
     * constructing the real fetcher — this loader follows the same
     * two-phase shape: a free validity check first, the real (and
     * genuinely single-flighted) upstream call only on a confirmed miss.</p>
     */
    private CompletableFuture<Outcome> fetch(
        final String path, final String module, final Key key
    ) {
        final CacheControl control = this.storage
            .<CacheControl>map(CacheTimeControl::new)
            .orElse(CacheControl.Standard.ALWAYS);
        return this.cache.load(key, Remote.EMPTY, control)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    return cached.get().asBytesFuture()
                        .thenApply(bytes -> Outcome.available(bytes, false));
                }
                return this.fetchFromUpstream(path, module, key, control);
            })
            .toCompletableFuture();
    }

    /**
     * Confirmed miss (absent or TTL-expired) — perform the real upstream
     * fetch, cache it on success, and fall back to a stale copy (if any)
     * on failure.
     */
    private CompletableFuture<Outcome> fetchFromUpstream(
        final String path, final String module, final Key key, final CacheControl control
    ) {
        final RequestLine line = new RequestLine(RqMethod.GET, path);
        final AtomicReference<Outcome> forward = new AtomicReference<>(
            Outcome.unavailable(RsStatus.BAD_GATEWAY, UPSTREAM_UNAVAILABLE_BODY)
        );
        final Remote remote = () -> this.upstream.response(line, Headers.EMPTY, Content.EMPTY)
            .thenCompose(resp -> resp.body().asBytesFuture()
                .<Optional<? extends Content>>thenApply(bytes -> {
                    if (resp.status().success()) {
                        return Optional.of(new Content.From(bytes));
                    }
                    forward.set(Outcome.unavailable(resp.status(), bytes));
                    return Optional.empty();
                }))
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.http.cooldown")
                    .message("Go metadata base fetch failed")
                    .eventCategory("web")
                    .eventAction("metadata_base_fetch")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("url.path", path)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return Optional.empty();
            });
        return this.cache.load(key, remote, control)
            .thenCompose(opt -> {
                if (opt.isPresent()) {
                    return opt.get().asBytesFuture()
                        .thenApply(bytes -> Outcome.available(bytes, false));
                }
                return this.staleFallback(key, module, path, forward.get());
            })
            .toCompletableFuture();
    }

    /**
     * After {@link Cache#load} comes back empty (nothing cached and
     * upstream failed, or a stale entry existed but upstream also
     * failed while refreshing it), check storage directly for whatever
     * bytes are still on disk — {@link Cache#load}'s TTL-expiry path
     * never deletes a stale entry, it just declines to serve it without
     * a successful refresh — and serve those as a degraded response.
     */
    private CompletableFuture<Outcome> staleFallback(
        final Key key, final String module, final String path, final Outcome forward
    ) {
        if (this.storage.isEmpty()) {
            return CompletableFuture.completedFuture(forward);
        }
        final Storage raw = this.storage.get();
        return raw.exists(key).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture(forward);
            }
            return raw.value(key).thenCompose(Content::asBytesFuture).thenApply(bytes -> {
                EcsLogger.info("com.auto1.pantera.http.cooldown")
                    .message("Serving stale Go metadata base after upstream failure")
                    .eventCategory("web")
                    .eventAction("serve_stale")
                    .eventOutcome("success")
                    .field("event.reason", "upstream_unavailable")
                    .field("repository.name", this.repoName)
                    .field("package.name", module)
                    .field("url.path", path)
                    .field("log.source", "application")
                    .log();
                return Outcome.available(bytes, true);
            });
        }).exceptionally(err -> forward);
    }

    /**
     * Outcome of resolving a Go metadata base document: either the
     * document bytes (fresh, cache-hit, or a stale fallback), or a
     * status + body to forward verbatim when nothing is available
     * anywhere.
     *
     * @since 2.3.0
     */
    public static final class Outcome {

        /**
         * Document bytes, non-null only when {@link #isAvailable()}.
         */
        private final byte[] body;

        /**
         * True when {@link #body} was served from a stale (TTL-expired)
         * cache entry because the upstream refresh failed.
         */
        private final boolean stale;

        /**
         * Status to forward when nothing is available. Null when
         * {@link #isAvailable()}.
         */
        private final RsStatus status;

        /**
         * Body to forward alongside {@link #status}. Null when
         * {@link #isAvailable()}.
         */
        private final byte[] errorBody;

        private Outcome(
            final byte[] body, final boolean stale,
            final RsStatus status, final byte[] errorBody
        ) {
            this.body = body == null ? null : Arrays.copyOf(body, body.length);
            this.stale = stale;
            this.status = status;
            this.errorBody = errorBody == null ? null : Arrays.copyOf(errorBody, errorBody.length);
        }

        private static Outcome available(final byte[] body, final boolean stale) {
            return new Outcome(body, stale, null, null);
        }

        private static Outcome unavailable(final RsStatus status, final byte[] body) {
            return new Outcome(null, false, status, body);
        }

        /**
         * @return true when {@link #body()} carries a servable document
         */
        public boolean isAvailable() {
            return this.body != null;
        }

        /**
         * @return document bytes; only meaningful when {@link #isAvailable()}
         */
        public byte[] body() {
            return this.body == null ? null : Arrays.copyOf(this.body, this.body.length);
        }

        /**
         * @return true when {@link #body()} is a stale, TTL-expired copy
         *         served because the upstream refresh failed
         */
        public boolean stale() {
            return this.stale;
        }

        /**
         * @return status to forward; only meaningful when {@code !isAvailable()}
         */
        public RsStatus status() {
            return this.status;
        }

        /**
         * @return body to forward alongside {@link #status()}; only
         *         meaningful when {@code !isAvailable()}
         */
        public byte[] errorBody() {
            return this.errorBody == null ? null : Arrays.copyOf(this.errorBody, this.errorBody.length);
        }
    }
}
