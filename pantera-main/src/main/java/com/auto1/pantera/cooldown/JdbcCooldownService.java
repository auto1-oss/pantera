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

import com.auto1.pantera.cooldown.api.CooldownBlock;
import com.auto1.pantera.cooldown.api.CooldownInspector;
import com.auto1.pantera.cooldown.api.CooldownReason;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownResult;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.config.CooldownCircuitBreaker;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.config.InspectorRegistry;
import com.auto1.pantera.cooldown.metadata.FilteredMetadataCache;
import com.auto1.pantera.cooldown.metrics.CooldownMetrics;
import com.auto1.pantera.http.log.EcsLogger;
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
                    .log();
            } catch (Exception e) {
                EcsLogger.error("com.auto1.pantera.cooldown")
                    .message("Failed to initialize cooldown metrics")
                    .eventCategory("database")
                    .eventAction("metrics_init")
                    .error(e)
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
            .log();
        
        // Use cache (3-tier: L1 -> L2 -> Database)
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
                    .log();
                
                if (rec.status() == BlockStatus.ACTIVE) {
                    // Check if block has expired
                    final Instant now = Instant.now();
                    if (rec.blockedUntil().isBefore(now)) {
                        EcsLogger.info("com.auto1.pantera.cooldown")
                            .message(String.format(
                                "Block has EXPIRED - allowing artifact (blockedUntil=%s)",
                                rec.blockedUntil()))
                            .eventCategory("database")
                            .eventAction("block_expired")
                            .field("package.name", request.artifact())
                            .field("package.version", request.version())
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
                if (record.blockedUntil().isAfter(now)) {
                    // Blocked with expiration timestamp
                    return Optional.of(new BlockCacheEntry(true, record.blockedUntil()));
                }
                this.expire(record, now);
                // Expired block = allowed
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
        // Async fetch release date with timeout to prevent hanging
        return inspector.releaseDate(request.artifact(), request.version())
            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .exceptionally(error -> {
                EcsLogger.warn("com.auto1.pantera.cooldown")
                    .message("Failed to fetch release date (allowing)")
                    .eventCategory("database")
                    .eventAction("release_date_fetch")
                    .eventOutcome("failure")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("error.message", error.getMessage())
                    .log();
                return Optional.empty();
            })
            .thenCompose(release -> {
                return this.shouldBlockNewArtifact(request, inspector, release);
            });
    }

    /**
     * Check if new artifact should be blocked given a known release date.
     * Returns boolean and creates database record if blocking.
     * @param request Cooldown request
     * @param inspector Inspector for dependencies
     * @param release Release date (may be empty)
     * @return CompletableFuture with boolean (true=blocked, false=allowed)
     */
    private CompletableFuture<Boolean> shouldBlockNewArtifact(
        final CooldownRequest request,
        final CooldownInspector inspector,
        final Optional<Instant> release
    ) {
        final Instant now = request.requestedAt();
        
        if (release.isEmpty()) {
            EcsLogger.debug("com.auto1.pantera.cooldown")
                .message("No release date found - allowing")
                .eventCategory("database")
                .eventAction("allowed")
                .eventOutcome("success")
                .field("repository.type", request.repoType())
                .field("repository.name", request.repoName())
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .log();
            this.cache.put(request.repoName(), request.artifact(), request.version(), false);
            return CompletableFuture.completedFuture(false);
        }

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
                .log();
            // Create block in database (async)
            return this.createBlockInDatabase(request, CooldownReason.FRESH_RELEASE, until)
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
        final Instant blockedUntil
    ) {
        return CompletableFuture.supplyAsync(() -> {
            final Instant now = request.requestedAt();
            // Pass the user who tried to install as installed_by
            final Optional<String> installedBy = Optional.ofNullable(request.requestedBy())
                .filter(s -> !s.isEmpty() && !s.equals("anonymous"));
            this.repository.insertBlock(
                request.repoType(),
                request.repoName(),
                request.artifact(),
                request.version(),
                reason,
                now,
                blockedUntil,
                SYSTEM_ACTOR,
                installedBy
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
     * Whether cooldown enforcement is active for this request.
     * Per-repo-name override (highest priority) → per-type → global.
     */
    private boolean effectiveEnabled(final CooldownRequest request) {
        if (this.settings.isRepoNameOverridePresent(request.repoName())) {
            return this.settings.enabledForRepoName(request.repoName());
        }
        return this.settings.enabledFor(request.repoType());
    }

    /**
     * Effective minimum allowed age for this request.
     * Per-repo-name override (highest priority) → per-type → global.
     */
    private Duration effectiveDuration(final CooldownRequest request) {
        if (this.settings.isRepoNameOverridePresent(request.repoName())) {
            return this.settings.minimumAllowedAgeForRepoName(request.repoName());
        }
        return this.settings.minimumAllowedAgeFor(request.repoType());
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
            .log();
        this.repository.archiveAndDelete(
            record.id(),
            ArchiveReason.EXPIRED,
            SYSTEM_ACTOR);
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
                .log();
        }
        // Invalidate inspector cache (same as unblockSingle does)
        InspectorRegistry.instance()
            .invalidate(record.repoType(), record.repoName(),
                record.artifact(), record.version());
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
        
        // Invalidate inspector cache (works for all adapters: Docker, NPM, PyPI, etc.)
        InspectorRegistry.instance()
            .invalidate(repoType, repoName, artifact, version);
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
            EcsLogger.info("com.auto1.pantera.cooldown")
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
                .log();
        }
        // Single bulk archive+delete instead of N individual updates so that
        // every unblocked row leaves a MANUAL_UNBLOCK history trail.
        final int count = this.repository.archiveAndDeleteByRepo(
            repoType, repoName, ArchiveReason.MANUAL_UNBLOCK, actor);
        // Clear inspector cache (works for all adapters: Docker, NPM, PyPI, etc.)
        InspectorRegistry.instance()
            .clearAll(repoType, repoName);
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
            .log();
        this.repository.archiveAndDelete(
            record.id(),
            ArchiveReason.MANUAL_UNBLOCK,
            actor);
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
                .log();
        }
    }
}
