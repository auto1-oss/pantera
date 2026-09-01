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
package com.auto1.pantera.cooldown;

import com.auto1.pantera.cache.CacheInvalidationPubSub;
import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownCircuitBreaker;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metrics.CooldownMetrics;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.resilience.SingleFlight;
import com.auto1.pantera.publishdate.PublishDateRegistries;
import com.auto1.pantera.publishdate.PublishDateRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;


final class JdbcCooldownService implements CooldownService {

    private final CooldownSettings settings;
    private final CooldownRepository repository;
    private final Executor executor;
    private final CooldownCache cache;
    private final CooldownCircuitBreaker circuitBreaker;

    /**
     * Per-key single-flight for {@link #evaluate}. Closes the
     * thundering-herd window between L1-cooldown-miss and the
     * DB lookup that follows: N concurrent callers asking about the
     * same {@link CooldownKey} share one downstream evaluation.
     *
     * <p>TTL 30 s is comfortably above the {@code inspector.releaseDate}
     * 1.7 s timeout in {@link #checkNewArtifactAndCache} and the
     * synchronous DB calls in {@link #checkExistingBlockWithTimestamp}.
     * Max 10000 distinct keys is well above any realistic per-instance
     * burst — Caffeine's LRU eviction handles overflow without
     * affecting in-flight callers.
     *
     * <p>The coalescer holds <em>in-flight work only</em>; result
     * caching is handled by the existing 3-tier {@link CooldownCache}.
     */
    private final SingleFlight<CooldownKey, CooldownResult> evaluateSingleFlight;

    /**
     * Callback invoked when a cooldown block expires or is removed,
     * to invalidate the filtered metadata cache. Without this, the
     * metadata cache continues to serve a response with the version
     * stripped out even after the block is gone.
     *
     * <p>Set via {@link #setOnBlockRemoved} after construction to
     * break the circular dependency between CooldownService and
     * CooldownMetadataService. Never null — defaults to no-op.</p>
     */
    private volatile OnBlockRemoved onBlockRemoved = OnBlockRemoved.NOOP;

    /**
     * Callback for metadata cache invalidation on block removal.
     * Called from {@link #expire} and {@link #checkExistingBlockWithTimestamp}
     * when a block is found to be expired.
     */
    @FunctionalInterface
    interface OnBlockRemoved {
        OnBlockRemoved NOOP = (repoType, repoName, artifact, version) -> { };
        void accept(String repoType, String repoName, String artifact, String version);
    }

    /**
     * Optional filtered-metadata envelope cache invalidator. When non-null,
     * every block state change (new block, unblock, bulk mark/unmark) fires
     * an invalidation so the envelope gets re-filtered on the next request
     * rather than serving a stale "0 blocked" snapshot frozen in Valkey.
     * Nullable: unit tests and the pre-2.2.0 wiring leave this as null.
     */
    private volatile FilteredMetadataCache envelopeInvalidator;

    /**
     * Optional cross-instance pub/sub for cache invalidation fan-out. When
     * non-null, every block state change broadcasts the per-version block
     * decision on {@link #CHANNEL_DECISIONS} (keyed by {@link
     * CooldownCache#blockKey}) so peers' Caffeine L1 entries drop
     * immediately rather than waiting on per-entry TTL. Envelope drops are
     * broadcast by {@link FilteredMetadataCache} itself (its invalidation
     * publisher, wired in {@code CooldownSupport}) with the exact dropped
     * keys — including every variant — so no per-key publish lives here
     * any more.
     *
     * <p>Nullable: single-instance deployments and unit tests leave this
     * as null. Self-message filtering is handled by {@code
     * CacheInvalidationPubSub} via instance UUID — no extra guard needed
     * here. The receive side calls {@link CooldownCache#invalidate(String)}
     * and {@link FilteredMetadataCache#invalidate(String)}, both of which
     * deliberately do <em>not</em> re-publish — that's the no-loop
     * guarantee for this wiring.</p>
     */
    private volatile CacheInvalidationPubSub pubsub;

    /**
     * Pub/sub channel name for cooldown decision (per-version block state)
     * invalidations. Keys are {@link CooldownCache#blockKey} outputs.
     */
    private static final String CHANNEL_DECISIONS = "cooldown-decisions";

    /**
     * Pub/sub channel name for filtered-metadata envelope invalidations.
     * Used here only for the bulk ({@code publishAll}) broadcast on
     * {@code unblockAll}; per-key envelope drops are published by
     * {@link FilteredMetadataCache} itself with the exact dropped keys.
     */
    private static final String CHANNEL_ENVELOPE = "cooldown-envelope";

    private static final String SYSTEM_ACTOR = "system";

    JdbcCooldownService(final CooldownSettings settings, final CooldownRepository repository) {
        this(settings, repository, ForkJoinPool.commonPool(), new CooldownCache(), new CooldownCircuitBreaker());
    }

    JdbcCooldownService(
        final CooldownSettings settings,
        final CooldownRepository repository,
        final Executor executor
    ) {
        this(settings, repository, executor, new CooldownCache(), new CooldownCircuitBreaker());
    }

    JdbcCooldownService(
        final CooldownSettings settings,
        final CooldownRepository repository,
        final Executor executor,
        final CooldownCache cache
    ) {
        this(settings, repository, executor, cache, new CooldownCircuitBreaker());
    }

    JdbcCooldownService(
        final CooldownSettings settings,
        final CooldownRepository repository,
        final Executor executor,
        final CooldownCache cache,
        final CooldownCircuitBreaker circuitBreaker
    ) {
        this.settings = Objects.requireNonNull(settings);
        this.repository = Objects.requireNonNull(repository);
        this.executor = com.auto1.pantera.http.context.ContextualExecutor
            .contextualize(Objects.requireNonNull(executor));
        this.cache = Objects.requireNonNull(cache);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.evaluateSingleFlight = new SingleFlight<>(
            Duration.ofSeconds(30), 10_000, this.executor
        );
    }

    /**
     * Coalescing key for {@link #evaluate}. Stable record of the
     * upstream-affecting tuple — {@code requestedAt} and
     * {@code requestedBy} deliberately excluded so concurrent callers
     * with slightly different request metadata still share the same
     * underlying evaluation.
     */
    private record CooldownKey(
        String repoType, String repoName, String artifact, String version
    ) {
        static CooldownKey of(final CooldownRequest request) {
            return new CooldownKey(
                request.repoType(), request.repoName(),
                request.artifact(), request.version()
            );
        }
    }

    /**
     * Get the cooldown cache instance.
     * Used by MetadataFilterService for cache sharing.
     * @return CooldownCache instance
     */
    public CooldownCache cache() {
        return this.cache;
    }

    /**
     * Set the callback invoked when a block is removed (expired or
     * released). The callback should invalidate the metadata cache
     * for the affected package so clients see the unblocked version
     * immediately instead of waiting for the metadata cache TTL.
     *
     * <p>Called from {@link CooldownSupport#createMetadataService}
     * after the metadata service is constructed, breaking the
     * circular dependency.</p>
     *
     * @param callback Block-removed callback
     */
    void setOnBlockRemoved(final OnBlockRemoved callback) {
        this.onBlockRemoved = callback != null ? callback : OnBlockRemoved.NOOP;
    }

    /**
     * Wire the filtered-metadata cache for envelope invalidation. Called
     * by CooldownSupport.createMetadataService after the cache instance
     * is constructed, since that happens AFTER the JdbcCooldownService
     * is built.
     *
     * @param cache Filtered-metadata cache to invalidate on block changes,
     *              or null to disable invalidation (no-op)
     */
    public void setEnvelopeInvalidator(final FilteredMetadataCache cache) {
        this.envelopeInvalidator = cache;
    }

    /**
     * Wire the cross-instance pub/sub bus for cache invalidation fan-out.
     * Called by {@code CooldownSupport.createMetadataService} when a Valkey
     * pub/sub is available. Null is well-tolerated: single-instance
     * deployments and unit tests skip this wiring and every publish becomes
     * a no-op.
     *
     * @param bus Pub/sub bus, or null to disable peer fan-out
     */
    public void setCacheInvalidationPubSub(final CacheInvalidationPubSub bus) {
        this.pubsub = bus;
    }

    /**
     * Broadcast a per-key cooldown-decisions invalidation. No-op if pub/sub
     * is unwired. Swallows any publish failure with a debug log — a Valkey
     * stutter must not break the state-change operation that triggered it.
     */
    private void publishDecisionInvalidation(
        final String repoName, final String artifact, final String version
    ) {
        final CacheInvalidationPubSub bus = this.pubsub; // NOPMD CloseResource - lifecycle owned by YamlSettings.cachePubSub (closed on shutdown); this is just a snapshot of the volatile field
        if (bus == null) {
            return;
        }
        try {
            bus.publish(CHANNEL_DECISIONS, this.cache.blockKey(repoName, artifact, version));
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("Published cooldown-decisions invalidation")
                .eventCategory("database")
                .eventAction("pubsub_publish")
                .eventOutcome("success")
                .field("repository.name", repoName)
                .field("package.name", artifact)
                .field("package.version", version)
                .field("log.source", "application")
                .log();
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("Failed to publish cooldown-decisions invalidation; peers will TTL-expire")
                .eventCategory("database")
                .eventAction("pubsub_publish")
                .eventOutcome("failure")
                .field("repository.name", repoName)
                .field("package.name", artifact)
                .field("package.version", version)
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Broadcast a bulk invalidation on both pub/sub channels. Used by
     * {@code unblockAll}; coarser than per-key but acceptable since
     * unblockAll is a rare admin op and the cooldown L1 namespace is
     * shared across repos.
     */
    private void publishBulkInvalidation() {
        final CacheInvalidationPubSub bus = this.pubsub; // NOPMD CloseResource - lifecycle owned by YamlSettings.cachePubSub (closed on shutdown); this is just a snapshot of the volatile field
        if (bus == null) {
            return;
        }
        try {
            bus.publishAll(CHANNEL_DECISIONS);
            bus.publishAll(CHANNEL_ENVELOPE);
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("Published bulk cooldown invalidation (decisions + envelope)")
                .eventCategory("database")
                .eventAction("pubsub_publish_all")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("Failed to publish bulk cooldown invalidation; peers will TTL-expire")
                .eventCategory("database")
                .eventAction("pubsub_publish_all")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Invalidate the filtered-metadata envelope for a single package.
     * Swallows exceptions and logs a WARN so that an invalidation failure
     * does not break the block-state-change operation that triggered it.
     *
     * @param repoType  Repository type (e.g. "maven-proxy")
     * @param repoName  Repository name (e.g. "central")
     * @param artifact  Package name (e.g. "com/google/guava/guava")
     */
    private void invalidateEnvelope(
        final String repoType, final String repoName, final String artifact
    ) {
        final FilteredMetadataCache cache = this.envelopeInvalidator;
        if (cache != null) {
            try {
                cache.invalidate(repoType, repoName, artifact);
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("Envelope invalidation failed; will expire via TTL")
                    .eventCategory("database")
                    .eventAction("envelope_invalidate")
                    .eventOutcome("failure")
                    .field("repository.type", repoType)
                    .field("repository.name", repoName)
                    .field("package.name", artifact)
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    /**
     * Invalidate all filtered-metadata envelopes for a repository.
     * Used when the entire repo is unblocked (unblockAll path).
     *
     * @param repoType Repository type
     * @param repoName Repository name
     */
    private void invalidateAllEnvelopes(final String repoType, final String repoName) {
        final FilteredMetadataCache cache = this.envelopeInvalidator;
        if (cache != null) {
            try {
                cache.invalidateAll(repoType, repoName);
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("Envelope invalidation (all) failed; will expire via TTL")
                    .eventCategory("database")
                    .eventAction("envelope_invalidate_all")
                    .eventOutcome("failure")
                    .field("repository.type", repoType)
                    .field("repository.name", repoName)
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    /**
     * Initialize metrics from database on startup.
     * Loads actual active block counts and updates gauges.
     * Should be called once after service construction.
     */
    public void initializeMetrics() {
        if (!CooldownMetrics.isAvailable()) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("CooldownMetrics not available - metrics will not be initialized")
                .eventCategory("database")
                .eventAction("metrics_init")
                .field("log.source", "application")
                .log();
            return;
        }
        // Eagerly get instance to ensure global gauges are registered even with 0 blocks
        final CooldownMetrics metrics = CooldownMetrics.getInstance();
        if (metrics == null) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("CooldownMetrics instance is null - metrics will not be initialized")
                .eventCategory("database")
                .eventAction("metrics_init")
                .field("log.source", "application")
                .log();
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                // Load active blocks per repo
                final Map<String, Long> counts = this.repository.countAllActiveBlocks();
                for (Map.Entry<String, Long> entry : counts.entrySet()) {
                    final String[] parts = entry.getKey().split(":", 2);
                    if (parts.length == 2) {
                        metrics.updateActiveBlocks(parts[0], parts[1], entry.getValue());
                    }
                }
                final long total = counts.values().stream().mapToLong(Long::longValue).sum();

                // Load all-blocked packages count
                final long allBlocked = this.repository.countAllBlockedPackages();
                metrics.setAllBlockedPackages(allBlocked);

                EcsLogger.info("com.auto1.pantera.cooldown")
                    .message(String.format(
                        "Initialized cooldown metrics from database: %d repositories, %d total blocks, %d all-blocked packages",
                        counts.size(), total, allBlocked))
                    .eventCategory("database")
                    .eventAction("metrics_init")
                    .field("log.source", "application")
                    .log();
            } catch (Exception e) {
                EcsLogger.error("com.auto1.pantera.cooldown")
                    .message("Failed to initialize cooldown metrics")
                    .eventCategory("database")
                    .eventAction("metrics_init")
                    .error(e)
                    .field("log.source", "application")
                    .field("event.outcome", "failure")
                    .log();
            }
        }, this.executor);
    }

    /**
     * Increment active blocks metric for a repository (O(1), no DB query).
     */
    private void incrementActiveBlocksMetric(final String repoType, final String repoName) {
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().incrementActiveBlocks(repoType, repoName);
        }
    }

    /**
     * Decrement active blocks metric for a repository (O(1), no DB query).
     */
    private void decrementActiveBlocksMetric(final String repoType, final String repoName) {
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().decrementActiveBlocks(repoType, repoName);
        }
    }

    /**
     * Record a version blocked event (counter metric).
     */
    private void recordVersionBlockedMetric(final String repoType, final String repoName) {
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordVersionBlocked(repoType, repoName);
        }
    }

    /**
     * Record a version allowed event (counter metric).
     */
    private void recordVersionAllowedMetric(final String repoType, final String repoName) {
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordVersionAllowed(repoType, repoName);
        }
    }

    @Override
    public CompletableFuture<CooldownResult> evaluate(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        // Check if cooldown is enabled (per-repo-name override beats per-type beats global)
        if (!this.effectiveEnabled(request)) {
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("Cooldown disabled for repo type - allowing")
                .eventCategory("database")
                .eventAction("allowed")
                .eventOutcome("success")
                .field("repository.type", request.repoType())
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        
        // Circuit breaker: Auto-allow if service is degraded
        if (!this.circuitBreaker.shouldEvaluate()) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("Circuit breaker OPEN - auto-allowing artifact")
                .eventCategory("database")
                .eventAction("allowed")
                .eventOutcome("success")
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        
        EcsLogger.debug("com.auto1.pantera.cooldown")
            .message("Evaluating cooldown for artifact")
            .eventCategory("database")
            .eventAction("evaluate")
            .field("repository.type", request.repoType())
            .field("repository.name", request.repoName())
            .field("package.name", request.artifact())
            .field("package.version", request.version())
            .field("log.source", "application")
            .log();

        return this.evaluateSingleFlight.load(
            CooldownKey.of(request),
            () -> this.evaluateCoalesced(request, inspector)
        );
    }

    @Override
    public CompletableFuture<CooldownResult> evaluateWithKnownDate(
        final CooldownRequest request,
        final Optional<Instant> knownReleaseDate
    ) {
        if (!this.effectiveEnabled(request)) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        if (!this.circuitBreaker.shouldEvaluate()) {
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        return this.evaluateSingleFlight.load(
            CooldownKey.of(request),
            () -> this.evaluateCoalescedKnownDate(request, knownReleaseDate)
        );
    }

    /**
     * Coalesced evaluation that bypasses the inspector network fetch.
     * The release date is supplied by the caller (extracted from the
     * already-parsed upstream packument) so the {@code orTimeout(1.7s)}
     * inspector path is avoided entirely on metadata-filter hot paths.
     */
    private CompletableFuture<CooldownResult> evaluateCoalescedKnownDate(
        final CooldownRequest request,
        final Optional<Instant> knownReleaseDate
    ) {
        return this.cache.isBlocked(
            request.repoName(),
            request.artifact(),
            request.version(),
            () -> CompletableFuture.supplyAsync(
                () -> this.checkExistingBlockWithTimestamp(request), this.executor
            ).thenCompose(existing -> {
                if (existing.isPresent()) {
                    final BlockCacheEntry entry = existing.get();
                    if (entry.blocked && entry.blockedUntil != null) {
                        this.cache.putBlocked(
                            request.repoName(), request.artifact(),
                            request.version(), entry.blockedUntil
                        );
                    } else {
                        this.cache.put(
                            request.repoName(), request.artifact(),
                            request.version(), entry.blocked
                        );
                    }
                    return CompletableFuture.completedFuture(entry.blocked);
                }
                return this.shouldBlockNewArtifact(request, knownReleaseDate);
            })
        ).thenCompose(blocked -> {
            if (blocked) {
                this.recordVersionBlockedMetric(request.repoType(), request.repoName());
                return this.getBlockResult(request);
            }
            this.recordVersionAllowedMetric(request.repoType(), request.repoName());
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }).whenComplete((result, error) -> {
            if (error != null) {
                this.circuitBreaker.recordFailure();
            } else {
                this.circuitBreaker.recordSuccess();
            }
        });
    }

    /**
     * Coalesced evaluation body — runs at most once per concurrent
     * burst sharing the same {@link CooldownKey}. The 3-tier
     * {@link CooldownCache} stays as the result store; this wrapper
     * only collapses the parallel cache-miss → DB-lookup window that
     * would otherwise produce N DB queries for the same package
     * version.
     */
    private CompletableFuture<CooldownResult> evaluateCoalesced(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        return this.cache.isBlocked(
            request.repoName(),
            request.artifact(),
            request.version(),
            () -> this.evaluateFromDatabase(request, inspector)
        ).thenCompose(blocked -> {
            if (blocked) {
                EcsLogger.info("com.auto1.pantera.cooldown")
                    .message("Artifact BLOCKED by cooldown (cache/db)")
                    .eventCategory("database")
                    .eventAction("evaluate")
                    .eventOutcome("failure")
                    .field("event.reason", "cooldown_active")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("log.source", "application")
                    .log();
                // Record blocked version counter metric
                this.recordVersionBlockedMetric(request.repoType(), request.repoName());
                // Blocked: Fetch full block details from database (async)
                return this.getBlockResult(request);
            } else {
                EcsLogger.debug("com.auto1.pantera.cooldown")
                    .message("Artifact ALLOWED by cooldown")
                    .eventCategory("database")
                    .eventAction("allowed")
                    .eventOutcome("success")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("log.source", "application")
                    .log();
                // Record allowed version counter metric
                this.recordVersionAllowedMetric(request.repoType(), request.repoName());
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                this.circuitBreaker.recordFailure();
                EcsLogger.error("com.auto1.pantera.cooldown")
                    .message("Cooldown evaluation failed")
                    .eventCategory("database")
                    .eventAction("evaluate")
                    .eventOutcome("failure")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("error.message", error.getMessage())
                    .field("log.source", "application")
                    .log();
            } else {
                this.circuitBreaker.recordSuccess();
            }
        });
    }

    @Override
    public CompletableFuture<Void> unblock(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final String actor
    ) {
        // Update cache to false first (immediate effect)
        this.cache.unblock(repoName, artifact, version);
        // Peer fan-out for the cooldown-decisions channel: covers the case where no DB
        // record exists (find() returns empty inside unblockSingle and release() never
        // runs) — without this, peer L1 entries would stick around until TTL even though
        // the local L1 was just cleared. When release() does run it also publishes; the
        // duplicate publish is harmless (self-message filtering is by instanceId).
        this.publishDecisionInvalidation(repoName, artifact, version);
        // Then update database and metrics
        return CompletableFuture.runAsync(
            () -> {
                this.unblockSingle(repoType, repoName, artifact, version, actor);
                // Decrement active blocks metric (O(1), no DB query)
                this.decrementActiveBlocksMetric(repoType, repoName);
                // Unmark all-blocked status and decrement metric
                this.unmarkAllBlockedPackage(repoType, repoName, artifact);
            },
            this.executor
        );
    }

    @Override
    public CompletableFuture<Void> unblockAll(
        final String repoType,
        final String repoName,
        final String actor
    ) {
        // Update all cache entries to false (immediate effect)
        this.cache.unblockAll(repoName);
        // Peer fan-out: broadcast bulk invalidation on both channels. unblockAll is rare
        // (admin op) so coarse broadcast (peers drop all cooldown-decision + envelope L1
        // entries, not just this repo's) is acceptable — the alternative is keying every
        // entry, which costs an L1 scan on every peer.
        this.publishBulkInvalidation();
        // Then update database and metrics
        return CompletableFuture.runAsync(
            () -> {
                final int unblockedCount = this.unblockAllBlocking(repoType, repoName, actor);
                // Decrement active blocks metric by count (O(1), no DB query)
                for (int i = 0; i < unblockedCount; i++) {
                    this.decrementActiveBlocksMetric(repoType, repoName);
                }
                // Unmark all all-blocked packages in this repo and update metric
                this.unmarkAllBlockedForRepo(repoType, repoName);
                // Envelope cache invalidation (coherency): drop all cached filtered-metadata
                // envelopes for the repo unconditionally — active per-version blocks have been
                // cleared so every package's next metadata request must re-filter.
                this.invalidateAllEnvelopes(repoType, repoName);
            },
            this.executor
        );
    }

    @Override
    public CompletableFuture<List<CooldownBlock>> activeBlocks(
        final String repoType,
        final String repoName
    ) {
        return CompletableFuture.supplyAsync(
            () -> this.repository.findActiveForRepo(repoType, repoName).stream()
                .filter(record -> record.status() == BlockStatus.ACTIVE)
                .map(this::toCooldownBlock)
                .collect(Collectors.toList()),
            this.executor
        );
    }

    /**
     * Query database and evaluate if artifact should be blocked.
     * Returns true if blocked, false if allowed.
     * @param request Cooldown request
     * @param inspector Inspector for artifact metadata
     * @return CompletableFuture with boolean result
     */
    private CompletableFuture<Boolean> evaluateFromDatabase(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        // Step 1: Check database for existing block (async)
        return CompletableFuture.supplyAsync(() -> {
            return this.checkExistingBlockWithTimestamp(request);
        }, this.executor).thenCompose(result -> {
            if (result.isPresent()) {
                final BlockCacheEntry entry = result.get();
                EcsLogger.debug("com.auto1.pantera.cooldown")
                    .message((entry.blocked ? "Database block found" : "Database no block") + " (blocked: " + entry.blocked + ")")
                    .eventCategory("database")
                    .eventAction("db_check")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("log.source", "application")
                    .log();
                // Cache the result with appropriate TTL
                if (entry.blocked && entry.blockedUntil != null) {
                    this.cache.putBlocked(request.repoName(), request.artifact(),
                        request.version(), entry.blockedUntil);
                } else {
                    this.cache.put(request.repoName(), request.artifact(),
                        request.version(), entry.blocked);
                }
                return CompletableFuture.completedFuture(entry.blocked);
            }
            // Step 2: No existing block - check if artifact should be blocked
            return this.checkNewArtifactAndCache(request, inspector);
        });
    }

    /**
     * Get full block result with details from database.
     * Only called when cache says artifact is blocked.
     */
    private CompletableFuture<CooldownResult> getBlockResult(final CooldownRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            final Optional<DbBlockRecord> record = this.repository.find(
                request.repoType(),
                request.repoName(),
                request.artifact(),
                request.version()
            );
            if (record.isPresent()) {
                final DbBlockRecord rec = record.get();
                EcsLogger.info("com.auto1.pantera.cooldown")
                    .message(String.format(
                        "Block record found in database: status=%s, reason=%s, blockedAt=%s, blockedUntil=%s",
                        rec.status().name(), rec.reason().name(), rec.blockedAt(), rec.blockedUntil()))
                    .eventCategory("database")
                    .eventAction("block_lookup")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("log.source", "application")
                    .log();
                
                if (rec.status() == BlockStatus.ACTIVE) {
                    // DYNAMIC re-evaluation against current
                    // minimumAllowedAge — see
                    // checkExistingBlockWithTimestamp for the rationale.
                    // When release_date is unknown, fall back to the
                    // stored blocked_until.
                    final Instant now = Instant.now();
                    final Instant effectiveBlockedUntil = rec.releaseDate()
                        .map(rd -> rd.plus(this.effectiveDuration(request)))
                        .orElse(rec.blockedUntil());
                    if (effectiveBlockedUntil.isBefore(now)) {
                        EcsLogger.info("com.auto1.pantera.cooldown")
                            .message(String.format(
                                "Block has EXPIRED (effective blockedUntil=%s by current policy; "
                                    + "stored=%s) — allowing artifact",
                                effectiveBlockedUntil, rec.blockedUntil()))
                            .eventCategory("database")
                            .eventAction("block_expired")
                            .field("package.name", request.artifact())
                            .field("package.version", request.version())
                            .field("log.source", "application")
                            .log();
                        // Expire the block
                        this.expire(rec, now);
                        // Update cache to allowed
                        this.cache.put(request.repoName(), request.artifact(), request.version(), false);
                        return CooldownResult.allowed();
                    }
                    return CooldownResult.blocked(this.toCooldownBlock(rec));
                }
            } else {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("Cache said blocked but no DB record found - allowing")
                    .eventCategory("database")
                    .eventAction("block_lookup")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("log.source", "application")
                    .log();
            }
            return CooldownResult.allowed();
        }, this.executor);
    }

    /**
     * Simple tuple for cache entry with timestamp.
     */
    private static class BlockCacheEntry {
        final boolean blocked;
        final Instant blockedUntil;
        
        BlockCacheEntry(boolean blocked, Instant blockedUntil) {
            this.blocked = blocked;
            this.blockedUntil = blockedUntil;
        }
    }
    
    /**
     * Check if artifact has existing block in database.
     * Returns cache entry with block status and expiration.
     * @param request Cooldown request
     * @return Optional with cache entry if block exists
     */
    private Optional<BlockCacheEntry> checkExistingBlockWithTimestamp(final CooldownRequest request) {
        final Instant now = request.requestedAt();
        final Optional<DbBlockRecord> existing = this.repository.find(
            request.repoType(),
            request.repoName(),
            request.artifact(),
            request.version()
        );
        if (existing.isPresent()) {
            final DbBlockRecord record = existing.get();
            if (record.status() == BlockStatus.ACTIVE) {
                // DYNAMIC re-evaluation against the CURRENT
                // minimumAllowedAge config — admin lowering the cooldown
                // duration from (say) 30 d to 15 d should release blocks
                // that no longer qualify under the new policy on the very
                // next evaluation, not wait out the stale 30 d window
                // baked into `blocked_until` at creation time.
                //
                // Re-evaluation requires release_date. When release_date
                // is unknown (older block rows that pre-date Track-5
                // Phase-1B) we fall back to the stored `blocked_until`
                // — same behaviour as before this change.
                final Instant effectiveBlockedUntil = record.releaseDate()
                    .map(rd -> rd.plus(this.effectiveDuration(request)))
                    .orElse(record.blockedUntil());
                if (effectiveBlockedUntil.isAfter(now)) {
                    return Optional.of(new BlockCacheEntry(true, effectiveBlockedUntil));
                }
                // Current policy says this block is no longer warranted
                // (either time elapsed or duration shortened by config).
                // Archive the row and treat as allowed.
                this.expire(record, now);
                return Optional.of(new BlockCacheEntry(false, null));
            }
            // Inactive block = allowed
            return Optional.of(new BlockCacheEntry(false, null));
        }
        return Optional.empty();
    }

    /**
     * Check if new artifact should be blocked and cache result.
     * @param request Cooldown request
     * @param inspector Inspector for artifact metadata
     * @return CompletableFuture with boolean (true=blocked, false=allowed)
     */
    private CompletableFuture<Boolean> checkNewArtifactAndCache(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        // Async fetch release date with timeout to prevent hanging.
        // Budget is slightly larger than the per-source HTTP timeout so the
        // source's own timeout fires (populating its negative cache) rather
        // than the parent cancelling first and leaving the source dangling.
        return inspector.releaseDate(request.artifact(), request.version())
            .orTimeout(1_700, java.util.concurrent.TimeUnit.MILLISECONDS)
            .exceptionally(error -> {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("Failed to fetch release date (allowing)")
                    .eventCategory("database")
                    .eventAction("release_date_fetch")
                    .eventOutcome("failure")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .error(error)
                    .field("log.source", "application")
                    .log();
                return Optional.empty();
            })
            .thenCompose(release -> {
                    return this.shouldBlockNewArtifact(request, release);
            });
    }

    /**
     * Check if new artifact should be blocked given a known release date.
     * Returns boolean and creates database record if blocking.
     * @param request Cooldown request
     * @param release Release date (may be empty)
     * @return CompletableFuture with boolean (true=blocked, false=allowed)
     */
    private CompletableFuture<Boolean> shouldBlockNewArtifact(
        final CooldownRequest request,
        final Optional<Instant> release
    ) {
        if (release.isEmpty()) {
            // RCA-pypi-B (v2.2.0): when the caller couldn't supply an inline
            // release date — e.g. PypiSimpleHandler when upstream's PEP 503
            // HTML omits data-upload-time, or any future adapter handing us
            // Optional.empty() — consult the canonical artifact_publish_dates
            // row before silently allowing. The row is already populated by
            // the cache-write event pipeline (see PublishDateExtractors), so
            // this is a pure-local lookup. CACHE_ONLY mode prevents a
            // fallback upstream HEAD even if the registry has a network
            // source registered for this repo type — keeps the contract that
            // metadata-filter is zero-extra-RTT.
            return PublishDateRegistries.instance()
                .publishDate(
                    request.repoType(),
                    request.artifact(),
                    request.version(),
                    PublishDateRegistry.Mode.CACHE_ONLY
                )
                .exceptionally(ex -> Optional.empty())
                .thenCompose(dbDate -> {
                    if (dbDate.isPresent()) {
                        return this.shouldBlockNewArtifact(request, dbDate);
                    }
                    EcsLogger.debug("com.auto1.pantera.cooldown")
                        .message("No release date found - allowing")
                        .eventCategory("database")
                        .eventAction("allowed")
                        .eventOutcome("success")
                        .field("repository.type", request.repoType())
                        .field("repository.name", request.repoName())
                        .field("package.name", request.artifact())
                        .field("package.version", request.version())
                        .field("log.source", "application")
                        .log();
                    this.cache.put(request.repoName(), request.artifact(), request.version(), false);
                    return CompletableFuture.completedFuture(false);
                });
        }
        final Instant now = request.requestedAt();

        // Use per-repo-name duration if configured, otherwise per-type, otherwise global
        final Duration fresh = this.effectiveDuration(request);
        final Instant date = release.get();
        
        // Debug logging to diagnose blocking decisions
        EcsLogger.info("com.auto1.pantera.cooldown")
            .message(String.format(
                "Evaluating freshness: cooldown=%s, release+cooldown=%s, requestTime=%s, isFresh=%s",
                fresh, date.plus(fresh), now, date.plus(fresh).isAfter(now)))
            .eventCategory("database")
            .eventAction("freshness_check")
            .field("package.name", request.artifact())
            .field("package.version", request.version())
            .field("package.release_date", date.toString())
            .field("log.source", "application")
            .log();

        if (date.plus(fresh).isAfter(now)
            && !fresh.isZero() && !fresh.isNegative()) {
            final Instant until = date.plus(fresh);
            EcsLogger.info("com.auto1.pantera.cooldown")
                .message("BLOCKING artifact - too fresh (released: " + date.toString() + ", blocked until: " + until.toString() + ")")
                .eventCategory("database")
                .eventAction("evaluate")
                .eventOutcome("failure")
                .field("event.reason", "cooldown_active")
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .field("package.release_date", date.toString())
                .field("log.source", "application")
                .log();
            // Create block in database (async)
            return this.createBlockInDatabase(request, CooldownReason.FRESH_RELEASE, until, release)
                .thenApply(success -> {
                    // Cache as blocked with dynamic TTL (until block expires)
                    this.cache.putBlocked(request.repoName(), request.artifact(),
                        request.version(), until);
                    return true;
                })
                .exceptionally(error -> {
                    EcsLogger.error("com.auto1.pantera.cooldown")
                        .message("Failed to create block (blocking anyway)")
                        .eventCategory("database")
                        .eventAction("block_create")
                        .eventOutcome("failure")
                        .field("package.name", request.artifact())
                        .field("package.version", request.version())
                        .field("error.message", error.getMessage())
                        .field("log.source", "application")
                        .log();
                    // Still cache as blocked with dynamic TTL
                    this.cache.putBlocked(request.repoName(), request.artifact(),
                        request.version(), until);
                    return true;
                });
        }

        EcsLogger.debug("com.auto1.pantera.cooldown")
            .message("ALLOWING artifact - old enough")
            .eventCategory("database")
            .eventAction("allowed")
            .eventOutcome("success")
            .field("package.name", request.artifact())
            .field("package.version", request.version())
            .field("package.release_date", date.toString())
            .field("package.age", Duration.between(date, now).getSeconds())
            .field("log.source", "application")
            .log();
        this.cache.put(request.repoName(), request.artifact(), request.version(), false);
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Create block record in database.
     * @param request Cooldown request
     * @param reason Block reason
     * @param blockedUntil Block expiration time
     * @return CompletableFuture<Boolean> (always returns true)
     */
    private CompletableFuture<Boolean> createBlockInDatabase(
        final CooldownRequest request,
        final CooldownReason reason,
        final Instant blockedUntil,
        final Optional<Instant> releaseDate
    ) {
        return CompletableFuture.supplyAsync(() -> {
            final Instant now = request.requestedAt();
            // Pass the user who tried to install as installed_by
            final Optional<String> installedBy = Optional.ofNullable(request.requestedBy())
                .filter(s -> !s.isEmpty() && !"anonymous".equals(s));
            this.repository.insertBlock(
                request.repoType(),
                request.repoName(),
                request.artifact(),
                request.version(),
                reason,
                now,
                blockedUntil,
                SYSTEM_ACTOR,
                installedBy,
                releaseDate
            );
            return true;
        }, this.executor).thenApply(result -> {
            // Increment active blocks metric (O(1), no DB query)
            this.incrementActiveBlocksMetric(request.repoType(), request.repoName());
            // Envelope cache invalidation (coherency): drop cached filtered metadata so next request
            // re-filters with the new block state rather than serving a stale "0 blocked" snapshot.
            this.invalidateEnvelope(request.repoType(), request.repoName(), request.artifact());
            return result;
        });
    }

    /**
     * Maven SNAPSHOT timestamp pattern used to recognise versions that should
     * honour the SNAPSHOT-specific knob. Matches the canonical form
     * {@code <base>-yyyyMMdd.HHmmss-N} (e.g. {@code 1.0-20260519.090000-1})
     * which is what {@code CachedProxySlice.extractSnapshotVersion} emits.
     */
    private static final java.util.regex.Pattern SNAPSHOT_TIMESTAMP =
        java.util.regex.Pattern.compile(".+-\\d{8}\\.\\d{6}-\\d+$");

    /**
     * Whether cooldown enforcement is active for this request.
     * SNAPSHOT precedence: per-repo SNAPSHOT override → per-repo override →
     * global SNAPSHOT policy → per-type override → global. Non-SNAPSHOT
     * versions delegate to {@link CooldownSettings#effectiveEnabled(String, String)}
     * — the single source of truth for the per-name → per-type → global
     * chain shared with {@link com.auto1.pantera.cooldown.metadata.MetadataFilterService}.
     */
    boolean effectiveEnabled(final CooldownRequest request) {
        if (isSnapshotVersion(request.version())) {
            final CooldownSettings.SnapshotPolicy perRepo =
                this.settings.repoNameSnapshotOverrides().get(request.repoName());
            if (perRepo != null && perRepo.enabled().isPresent()) {
                return perRepo.enabled().get();
            }
            if (this.settings.isRepoNameOverridePresent(request.repoName())) {
                return this.settings.enabledForRepoName(request.repoName());
            }
            final Optional<Boolean> globalSnap = this.settings.snapshotPolicy().enabled();
            if (globalSnap.isPresent()) {
                return globalSnap.get();
            }
            return this.settings.enabledFor(request.repoType());
        }
        return this.settings.effectiveEnabled(request.repoType(), request.repoName());
    }

    /**
     * Effective minimum allowed age for this request. Same precedence ladder
     * as {@link #effectiveEnabled} — SNAPSHOT versions consult the SNAPSHOT
     * tiers first; the non-SNAPSHOT path delegates to
     * {@link CooldownSettings#effectiveMinimumAllowedAge(String, String)} so
     * request-time evaluation and metadata-filter pre-selection share a
     * single source of truth.
     */
    Duration effectiveDuration(final CooldownRequest request) {
        if (isSnapshotVersion(request.version())) {
            final CooldownSettings.SnapshotPolicy perRepo =
                this.settings.repoNameSnapshotOverrides().get(request.repoName());
            if (perRepo != null && perRepo.minimumAllowedAge().isPresent()) {
                return perRepo.minimumAllowedAge().get();
            }
            if (this.settings.isRepoNameOverridePresent(request.repoName())) {
                return this.settings.minimumAllowedAgeForRepoName(request.repoName());
            }
            final Optional<Duration> globalSnap = this.settings.snapshotPolicy().minimumAllowedAge();
            if (globalSnap.isPresent()) {
                return globalSnap.get();
            }
            return this.settings.minimumAllowedAgeFor(request.repoType());
        }
        return this.settings.effectiveMinimumAllowedAge(request.repoType(), request.repoName());
    }

    /**
     * @param version Cooldown request version
     * @return true if this is a Maven SNAPSHOT timestamp version
     */
    private static boolean isSnapshotVersion(final String version) {
        return version != null && SNAPSHOT_TIMESTAMP.matcher(version).matches();
    }

    private void expire(final DbBlockRecord record, final Instant when) {
        EcsLogger.info("com.auto1.pantera.cooldown")
            .message("Deleting expired cooldown block: reason=" + record.reason().name()
                + " blocked_at=" + record.blockedAt()
                + " blocked_until=" + record.blockedUntil()
                + " blocked_by=" + record.blockedBy()
                + " expired_at=" + when)
            .eventCategory("database")
            .eventAction("block_expired_delete")
            .field("package.name", record.artifact())
            .field("package.version", record.version())
            .field("repository.type", record.repoType())
            .field("repository.name", record.repoName())
            .field("log.source", "application")
            .log();
        this.repository.archiveAndDelete(
            record.id(),
            ArchiveReason.EXPIRED,
            SYSTEM_ACTOR);
        // Update the local L1 + L2 caches to "allowed". Without this the
        // cache keeps blocked=true after the DB row is archived, and the
        // very next request gets cache-hit→DB-miss and logs the WARN
        // "Cache said blocked but no DB record found - allowing". The
        // peer pubsub at the end of this method covers OTHER instances;
        // unblock() updates THIS instance.
        this.cache.unblock(record.repoName(), record.artifact(), record.version());
        // Decrement active blocks metric (O(1), no DB query)
        this.decrementActiveBlocksMetric(record.repoType(), record.repoName());
        // Envelope cache invalidation (coherency): drop cached filtered metadata so next request
        // re-filters with the new block state (block expired → version now visible in metadata).
        this.invalidateEnvelope(record.repoType(), record.repoName(), record.artifact());
        // Invalidate the filtered metadata cache so clients see the
        // unblocked version immediately. Without this, the metadata
        // cache serves the old filtered response (with the version
        // stripped out) until its TTL expires — which can be hours.
        // The L1 Caffeine cache is especially sticky because L2
        // purge doesn't clear it.
        try {
            this.onBlockRemoved.accept(
                record.repoType(), record.repoName(),
                record.artifact(), record.version()
            );
        } catch (final Exception err) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("Failed to invalidate metadata cache on block expiry")
                .eventCategory("database")
                .eventAction("metadata_cache_invalidate")
                .eventOutcome("failure")
                .field("package.name", record.artifact())
                .error(err)
                .field("log.source", "application")
                .log();
        }
        // Peer fan-out: local L1+L2 are already updated; broadcast so other
        // instances drop their L1 immediately rather than waiting on TTL.
        this.publishDecisionInvalidation(record.repoName(), record.artifact(), record.version());
    }

    private void unblockSingle(
        final String repoType,
        final String repoName,
        final String artifact,
        final String version,
        final String actor
    ) {
        final Optional<DbBlockRecord> record = this.repository.find(repoType, repoName, artifact, version);
        record.ifPresent(value -> this.release(value, actor, Instant.now()));
    }

    private int unblockAllBlocking(
        final String repoType,
        final String repoName,
        final String actor
    ) {
        final Instant now = Instant.now();
        // Log each active block before bulk delete
        final List<DbBlockRecord> blocks = this.repository.findActiveForRepo(repoType, repoName);
        for (final DbBlockRecord record : blocks) {
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("Deleting unblocked cooldown block (bulk unblock-all): reason=" + record.reason().name()
                    + " blocked_at=" + record.blockedAt()
                    + " blocked_until=" + record.blockedUntil()
                    + " blocked_by=" + record.blockedBy()
                    + " unblocked_by=" + actor
                    + " unblocked_at=" + now)
                .eventCategory("database")
                .eventAction("block_unblocked_delete")
                .field("package.name", record.artifact())
                .field("package.version", record.version())
                .field("repository.type", repoType)
                .field("repository.name", repoName)
                .field("log.source", "application")
                .log();
        }
        // Single bulk archive+delete instead of N individual updates so that
        // every unblocked row leaves a MANUAL_UNBLOCK history trail.
        final int count = this.repository.archiveAndDeleteByRepo(
            repoType, repoName, ArchiveReason.MANUAL_UNBLOCK, actor);
        return count;
    }

    private void release(final DbBlockRecord record, final String actor, final Instant when) {
        EcsLogger.info("com.auto1.pantera.cooldown")
            .message("Deleting unblocked cooldown block: reason=" + record.reason().name()
                + " blocked_at=" + record.blockedAt()
                + " blocked_until=" + record.blockedUntil()
                + " blocked_by=" + record.blockedBy()
                + " unblocked_by=" + actor
                + " unblocked_at=" + when)
            .eventCategory("database")
            .eventAction("block_unblocked_delete")
            .field("package.name", record.artifact())
            .field("package.version", record.version())
            .field("repository.type", record.repoType())
            .field("repository.name", record.repoName())
            .field("log.source", "application")
            .log();
        this.repository.archiveAndDelete(
            record.id(),
            ArchiveReason.MANUAL_UNBLOCK,
            actor);
        // Envelope cache invalidation (coherency): drop cached filtered metadata so next request
        // re-filters with the new block state (block released → version now visible in metadata).
        // Mirrors expire(): without this, manual unblock leaves the per-package envelope cache
        // stale until its TTL — clients keep seeing the version stripped out for up to an hour.
        this.invalidateEnvelope(record.repoType(), record.repoName(), record.artifact());
        // Invalidate the filtered metadata cache so clients see the unblocked version
        // immediately. Mirrors expire(): the L1 Caffeine cache is especially sticky because
        // L2 purge doesn't clear it, and the per-adapter metadata cache used by Maven, npm,
        // PyPI, Composer, Helm, etc. would otherwise stay stale for the cache TTL.
        try {
            this.onBlockRemoved.accept(
                record.repoType(), record.repoName(),
                record.artifact(), record.version()
            );
        } catch (final Exception err) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("Failed to invalidate metadata cache on manual unblock")
                .eventCategory("database")
                .eventAction("metadata_cache_invalidate")
                .eventOutcome("failure")
                .field("package.name", record.artifact())
                .error(err)
                .field("log.source", "application")
                .log();
        }
        // Peer fan-out: local L1+L2 are already updated; broadcast so other instances drop
        // their L1 immediately rather than waiting on TTL.
        this.publishDecisionInvalidation(record.repoName(), record.artifact(), record.version());
    }

    private CooldownBlock toCooldownBlock(final DbBlockRecord record) {
        return new CooldownBlock(
            record.repoType(),
            record.repoName(),
            record.artifact(),
            record.version(),
            record.reason(),
            record.blockedAt(),
            record.blockedUntil(),
            java.util.Collections.emptyList()  // No dependencies tracked anymore
        );
    }

    @Override
    public void markAllBlocked(final String repoType, final String repoName, final String artifact) {
        CompletableFuture.runAsync(() -> {
            try {
                final boolean inserted = this.repository.markAllBlocked(repoType, repoName, artifact);
                if (inserted && CooldownMetrics.isAvailable()) {
                    CooldownMetrics.getInstance().incrementAllBlocked();
                    EcsLogger.debug("com.auto1.pantera.cooldown")
                        .message("Marked package as all-blocked")
                        .eventCategory("database")
                        .eventAction("all_blocked_mark")
                        .field("repository.type", repoType)
                        .field("repository.name", repoName)
                        .field("package.name", artifact)
                        .field("log.source", "application")
                        .log();
                }
                if (inserted) {
                    // Envelope cache invalidation (coherency): drop cached filtered metadata so next
                    // request re-filters with the new block state (all versions now blocked).
                    this.invalidateEnvelope(repoType, repoName, artifact);
                }
            } catch (Exception e) {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("Failed to mark package as all-blocked")
                    .eventCategory("database")
                    .eventAction("all_blocked_mark")
                    .field("repository.type", repoType)
                    .field("package.name", artifact)
                    .error(e)
                    .field("log.source", "application")
                    .log();
            }
        }, this.executor);
    }

    /**
     * Unmark a package as "all versions blocked" and decrement metric.
     */
    private void unmarkAllBlockedPackage(final String repoType, final String repoName, final String artifact) {
        try {
            final boolean wasBlocked = this.repository.unmarkAllBlocked(repoType, repoName, artifact);
            if (wasBlocked && CooldownMetrics.isAvailable()) {
                CooldownMetrics.getInstance().decrementAllBlocked();
                EcsLogger.debug("com.auto1.pantera.cooldown")
                    .message("Unmarked package as all-blocked")
                    .eventCategory("database")
                    .eventAction("all_blocked_unmark")
                    .field("repository.type", repoType)
                    .field("repository.name", repoName)
                    .field("package.name", artifact)
                    .field("log.source", "application")
                    .log();
            }
            if (wasBlocked) {
                // Envelope cache invalidation (coherency): drop cached filtered metadata so next
                // request re-filters now that the package is no longer universally blocked.
                this.invalidateEnvelope(repoType, repoName, artifact);
            }
        } catch (Exception e) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("Failed to unmark package as all-blocked")
                .eventCategory("database")
                .eventAction("all_blocked_unmark")
                .field("repository.type", repoType)
                .field("package.name", artifact)
                .error(e)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Unmark all all-blocked packages for a repository (called on unblockAll).
     */
    private void unmarkAllBlockedForRepo(final String repoType, final String repoName) {
        try {
            final int count = this.repository.unmarkAllBlockedForRepo(repoType, repoName);
            if (count > 0 && CooldownMetrics.isAvailable()) {
                // Reload from database to ensure accuracy
                final long newTotal = this.repository.countAllBlockedPackages();
                CooldownMetrics.getInstance().setAllBlockedPackages(newTotal);
                EcsLogger.debug("com.auto1.pantera.cooldown")
                    .message(String.format(
                        "Unmarked all-blocked packages for repo: %d packages unmarked", count))
                    .eventCategory("database")
                    .eventAction("all_blocked_unmark_all")
                    .field("repository.type", repoType)
                    .field("repository.name", repoName)
                    .field("log.source", "application")
                    .log();
            }
            if (count > 0) {
                // Envelope cache invalidation (coherency): drop all cached filtered-metadata
                // envelopes for the repo so next requests re-filter with the cleared block state.
                this.invalidateAllEnvelopes(repoType, repoName);
            }
        } catch (Exception e) {
            EcsLogger.warn("com.auto1.pantera.cooldown")
                .message("Failed to unmark all-blocked packages for repo")
                .eventCategory("database")
                .eventAction("all_blocked_unmark_all")
                .field("repository.type", repoType)
                .field("repository.name", repoName)
                .error(e)
                .field("log.source", "application")
                .log();
        }
    }
}
