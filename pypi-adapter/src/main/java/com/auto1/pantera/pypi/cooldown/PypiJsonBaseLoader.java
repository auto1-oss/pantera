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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.pypi.http.CacheTimeControl;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-through, TTL-cached, single-flighted loader for the PyPI legacy
 * JSON API resolution surface — {@code /pypi/&lt;name&gt;/json} and
 * {@code /pypi/&lt;name&gt;/&lt;version&gt;/json}.
 *
 * <p>Before this class existed, {@link PypiJsonHandler} fetched this
 * document live from a raw upstream client slice
 * ({@code PyProxySlice.buildRoute}'s {@code jsonApiUpstream}, always
 * {@code pypi.org} regardless of the configured Simple-API mirror) on
 * <em>every</em> request — no cache, no TTL, no fallback. A pypi.org blip
 * broke {@code poetry} / {@code pip-tools} resolution even for a package
 * whose wheels were already fully cached locally, contradicting the
 * offline-safety the rest of the proxy provides (WS6.3 — the same gap
 * closed for Go's {@code @v/list}/{@code @latest} by
 * {@code GoMetadataBaseLoader}, whose shape this mirrors). This loader
 * closes it: the raw, unfiltered upstream body is cached through
 * {@link Cache} with a 12h {@link CacheTimeControl} TTL, concurrent misses
 * for the same document collapse onto one upstream call via
 * {@link SingleFlight}, and an upstream failure falls back to whatever is
 * on disk — stale, but available — as long as some copy exists.</p>
 *
 * <p>Callers ({@link PypiJsonHandler}) run per-request cooldown filtering
 * over the returned base bytes, so this loader deliberately caches the
 * <em>unfiltered</em> document — a cooldown lift/re-block is reflected on
 * the very next request without forcing a re-fetch.</p>
 *
 * @since 2.3.0
 */
public final class PypiJsonBaseLoader {

    /**
     * Body used when the upstream call itself failed before any HTTP
     * status was available (connection refused, timeout, ...) and no
     * cached copy exists to fall back to.
     */
    private static final byte[] UPSTREAM_UNAVAILABLE_BODY =
        "Upstream temporarily unavailable".getBytes(StandardCharsets.UTF_8);

    /**
     * Storage namespace the base documents are cached under — distinct
     * from the Simple-API index cache keys sharing the same repository
     * storage.
     */
    private static final String NAMESPACE = "json-api";

    /**
     * Upstream slice for the JSON API (always pypi.org, per
     * {@code PyProxySlice.buildRoute}).
     */
    private final Slice upstream;

    /**
     * Storage-backed cache the base documents are read through.
     */
    private final Cache cache;

    /**
     * Backing storage — used both to build the TTL {@link CacheControl}
     * and to read a stale fallback directly when {@link Cache#load} comes
     * back empty after an upstream failure.
     */
    private final Storage storage;

    /**
     * Per-document single-flight gate. Concurrent callers for the same
     * cold JSON-API document collapse onto a single upstream call;
     * followers park then receive the leader's result.
     */
    private final SingleFlight<Key, Outcome> singleFlight;

    /**
     * Repository name, for logging only.
     */
    private final String repoName;

    /**
     * New loader.
     *
     * @param upstream Upstream JSON-API slice
     * @param cache Storage-backed cache for the base documents
     * @param storage Backing storage (TTL + stale fallback)
     * @param repoName Repository name (logging only)
     */
    public PypiJsonBaseLoader(
        final Slice upstream,
        final Cache cache,
        final Storage storage,
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
     * @param path Request path, e.g. {@code /pypi/requests/json}
     * @return Future outcome — available (fresh, cache-hit, or stale
     *         fallback) or unavailable (nothing cached and upstream
     *         failed / returned non-2xx)
     */
    public CompletableFuture<Outcome> load(final String path) {
        final Key key = namespacedKey(path);
        return this.singleFlight.load(key, () -> this.fetch(path, key));
    }

    /**
     * Test-only: number of in-flight (or completed-but-not-yet-invalidated)
     * {@link #singleFlight} entries. {@link SingleFlight} invalidates a
     * key's entry asynchronously on completion, so a test that issues two
     * sequential {@link #load(String)} calls for the same path must poll
     * this down to zero between them — otherwise the second call can
     * observe the first call's still-cached (not yet invalidated) result
     * instead of genuinely re-invoking the loader.
     *
     * @return Estimated count of entries still held by the coalescer
     */
    int inFlightCount() {
        return this.singleFlight.inFlightCount();
    }

    /**
     * Loader body invoked at most once per single-flight burst. Mirrors
     * {@code GoMetadataBaseLoader.fetch}'s two-phase shape: a free
     * validity check via {@link Remote#EMPTY} first, the real (and
     * genuinely single-flighted) upstream call only on a confirmed miss —
     * {@link Cache#load} evaluates its {@code remote} argument eagerly
     * regardless of whether the cache-hit branch ends up being used.
     */
    private CompletableFuture<Outcome> fetch(final String path, final Key key) {
        final CacheControl control = new CacheTimeControl(this.storage);
        return this.cache.load(key, Remote.EMPTY, control)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    return cached.get().asBytesFuture()
                        .thenApply(bytes -> Outcome.available(bytes, false));
                }
                return this.fetchFromUpstream(path, key, control);
            })
            .toCompletableFuture();
    }

    /**
     * Confirmed miss (absent or TTL-expired) — perform the real upstream
     * fetch, cache it on success, and fall back to a stale copy (if any)
     * on failure.
     */
    private CompletableFuture<Outcome> fetchFromUpstream(
        final String path, final Key key, final CacheControl control
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
                    // Preserve a circuit-open marker through this funnel — a
                    // group resolver wrapping this handler must be able to
                    // tell "the breaker fast-failed locally" apart from "the
                    // upstream really failed" (see UpstreamCircuitOpenException).
                    // Collapsing it into a bare status would convict a
                    // healthy member on the breaker's own fast-fail evidence.
                    forward.set(Outcome.unavailable(resp.status(), resp.headers(), bytes));
                    return Optional.empty();
                }))
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.pypi.cooldown")
                    .message("PyPI JSON-API base fetch failed")
                    .eventCategory("web")
                    .eventAction("json_base_fetch")
                    .eventOutcome("failure")
                    .field("repository.name", this.repoName)
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
                return this.staleFallback(key, path, forward.get());
            })
            .toCompletableFuture();
    }

    /**
     * After {@link Cache#load} comes back empty (nothing cached and
     * upstream failed, or a stale entry existed but upstream also failed
     * while refreshing it), check storage directly for whatever bytes are
     * still on disk — {@link Cache#load}'s TTL-expiry path never deletes
     * a stale entry, it just declines to serve it without a successful
     * refresh — and serve those as a degraded response.
     */
    private CompletableFuture<Outcome> staleFallback(
        final Key key, final String path, final Outcome forward
    ) {
        return this.storage.exists(key).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture(forward);
            }
            return this.storage.value(key).thenCompose(Content::asBytesFuture).thenApply(bytes -> {
                EcsLogger.info("com.auto1.pantera.pypi.cooldown")
                    .message("Serving stale PyPI JSON-API base after upstream failure")
                    .eventCategory("web")
                    .eventAction("serve_stale")
                    .eventOutcome("success")
                    .field("event.reason", "upstream_unavailable")
                    .field("repository.name", this.repoName)
                    .field("url.path", path)
                    .field("log.source", "application")
                    .log();
                return Outcome.available(bytes, true);
            });
        }).exceptionally(err -> forward);
    }

    /**
     * Namespace the request path under {@value #NAMESPACE} so the JSON-API
     * base cache never collides with the Simple-API index cache keys
     * sharing the same repository storage.
     */
    private static Key namespacedKey(final String path) {
        final String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return new Key.From(NAMESPACE, trimmed);
    }

    /**
     * Outcome of resolving a PyPI JSON-API base document: either the
     * document bytes (fresh, cache-hit, or a stale fallback), or a status
     * + body to forward verbatim when nothing is available anywhere.
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

        /**
         * True when {@link #status} is a synthesised circuit-open 502
         * (the upstream response carried
         * {@link com.auto1.pantera.http.UpstreamCircuitOpenException#HEADER}).
         * A group resolver wrapping this handler must treat this as
         * "member skipped", never "member failed" — see
         * {@link com.auto1.pantera.http.UpstreamCircuitOpenException}.
         */
        private final boolean circuitOpen;

        /**
         * {@code Retry-After} seconds carried by a circuit-open response.
         * Zero when {@link #circuitOpen} is false or unknown.
         */
        private final long retryAfterSeconds;

        private Outcome(
            final byte[] body, final boolean stale,
            final RsStatus status, final byte[] errorBody,
            final boolean circuitOpen, final long retryAfterSeconds
        ) {
            this.body = body == null ? null : Arrays.copyOf(body, body.length);
            this.stale = stale;
            this.status = status;
            this.errorBody = errorBody == null ? null : Arrays.copyOf(errorBody, errorBody.length);
            this.circuitOpen = circuitOpen;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private static Outcome available(final byte[] body, final boolean stale) {
            return new Outcome(body, stale, null, null, false, 0L);
        }

        private static Outcome unavailable(final RsStatus status, final byte[] body) {
            return new Outcome(null, false, status, body, false, 0L);
        }

        /**
         * Build an unavailable outcome from a real upstream HTTP response,
         * preserving the circuit-open marker (and its {@code Retry-After})
         * if present, so the caller can re-attach it to the response it
         * forwards to the client — see the class-level javadoc.
         *
         * @param status Upstream response status
         * @param headers Upstream response headers
         * @param body Upstream response body
         * @return Unavailable outcome carrying the circuit-open marker
         */
        private static Outcome unavailable(
            final RsStatus status, final Headers headers, final byte[] body
        ) {
            final boolean circuitOpen = !headers.values(
                com.auto1.pantera.http.UpstreamCircuitOpenException.HEADER
            ).isEmpty();
            long retryAfterSeconds = 0L;
            if (circuitOpen) {
                final java.util.List<String> values = headers.values("Retry-After");
                if (!values.isEmpty()) {
                    try {
                        retryAfterSeconds = Long.parseLong(values.get(0).trim());
                    } catch (final NumberFormatException ignored) {
                        retryAfterSeconds = 0L;
                    }
                }
            }
            return new Outcome(null, false, status, body, circuitOpen, retryAfterSeconds);
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

        /**
         * @return true when {@link #status()} is a synthesised circuit-open
         *         502 the caller must re-mark on the response it forwards
         */
        public boolean circuitOpen() {
            return this.circuitOpen;
        }

        /**
         * @return {@code Retry-After} seconds to re-attach when
         *         {@link #circuitOpen()}; 0 when absent/unknown
         */
        public long retryAfterSeconds() {
            return this.retryAfterSeconds;
        }
    }
}
