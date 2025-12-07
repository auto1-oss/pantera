/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import com.artipie.http.log.EcsLogger;

final class JdbcCooldownService implements CooldownService {

    private final CooldownSettings settings;
    private final CooldownRepository repository;
    private final Executor executor;
    private final CooldownCache cache;
    private final CooldownCircuitBreaker circuitBreaker;

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
        this.executor = Objects.requireNonNull(executor);
        this.cache = Objects.requireNonNull(cache);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
    }

    /**
     * Get the cooldown cache instance.
     * Used by CooldownMetadataServiceImpl for cache sharing.
     * @return CooldownCache instance
     */
    public CooldownCache cache() {
        return this.cache;
    }

    @Override
    public CompletableFuture<CooldownResult> evaluate(
        final CooldownRequest request,
        final CooldownInspector inspector
    ) {
        // Check if cooldown is enabled for this repository type
        if (!this.settings.enabledFor(request.repoType())) {
            EcsLogger.debug("com.artipie.cooldown")
                .message("Cooldown disabled for repo type - allowing")
                .eventCategory("cooldown")
                .eventAction("evaluate")
                .eventOutcome("allowed")
                .field("repository.type", request.repoType())
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .log();
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        
        // Circuit breaker: Auto-allow if service is degraded
        if (!this.circuitBreaker.shouldEvaluate()) {
            EcsLogger.warn("com.artipie.cooldown")
                .message("Circuit breaker OPEN - auto-allowing artifact")
                .eventCategory("cooldown")
                .eventAction("evaluate")
                .eventOutcome("allowed")
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .log();
            return CompletableFuture.completedFuture(CooldownResult.allowed());
        }
        
        EcsLogger.debug("com.artipie.cooldown")
            .message("Evaluating cooldown for artifact")
            .eventCategory("cooldown")
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
                EcsLogger.info("com.artipie.cooldown")
                    .message("Artifact BLOCKED by cooldown (cache/db)")
                    .eventCategory("cooldown")
                    .eventAction("evaluate")
                    .eventOutcome("blocked")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .log();
                // Blocked: Fetch full block details from database (async)
                return this.getBlockResult(request);
            } else {
                EcsLogger.debug("com.artipie.cooldown")
                    .message("Artifact ALLOWED by cooldown")
                    .eventCategory("cooldown")
                    .eventAction("evaluate")
                    .eventOutcome("allowed")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .log();
                return CompletableFuture.completedFuture(CooldownResult.allowed());
            }
        }).whenComplete((result, error) -> {
            if (error != null) {
                this.circuitBreaker.recordFailure();
                EcsLogger.error("com.artipie.cooldown")
                    .message("Cooldown evaluation failed")
                    .eventCategory("cooldown")
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
        // Then update database
        return CompletableFuture.runAsync(
            () -> this.unblockSingle(repoType, repoName, artifact, version, actor),
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
        // Then update database
        return CompletableFuture.runAsync(
            () -> this.unblockAllBlocking(repoType, repoName, actor),
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
                .map(record -> this.toCooldownBlock(record, this.repository.dependenciesOf(record.id())))
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
                EcsLogger.debug("com.artipie.cooldown")
                    .message((entry.blocked ? "Database block found" : "Database no block") + " (blocked: " + entry.blocked + ")")
                    .eventCategory("cooldown")
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
                EcsLogger.info("com.artipie.cooldown")
                    .message("Block record found in database")
                    .eventCategory("cooldown")
                    .eventAction("block_lookup")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("block.status", rec.status().name())
                    .field("block.reason", rec.reason().name())
                    .field("block.blockedAt", rec.blockedAt().toString())
                    .field("block.blockedUntil", rec.blockedUntil().toString())
                    .log();
                
                if (rec.status() == BlockStatus.ACTIVE) {
                    // Check if block has expired
                    final Instant now = Instant.now();
                    if (rec.blockedUntil().isBefore(now)) {
                        EcsLogger.info("com.artipie.cooldown")
                            .message("Block has EXPIRED - allowing artifact")
                            .eventCategory("cooldown")
                            .eventAction("block_expired")
                            .field("package.name", request.artifact())
                            .field("package.version", request.version())
                            .field("block.blockedUntil", rec.blockedUntil().toString())
                            .log();
                        // Expire the block
                        this.expire(rec, now);
                        // Update cache to allowed
                        this.cache.put(request.repoName(), request.artifact(), request.version(), false);
                        return CooldownResult.allowed();
                    }
                    final List<DbBlockRecord> deps = this.repository.dependenciesOf(rec.id());
                    return CooldownResult.blocked(this.toCooldownBlock(rec, deps));
                }
            } else {
                EcsLogger.warn("com.artipie.cooldown")
                    .message("Cache said blocked but no DB record found - allowing")
                    .eventCategory("cooldown")
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
                    this.repository.recordAttempt(record.id(), request.requestedBy(), now);
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
                EcsLogger.warn("com.artipie.cooldown")
                    .message("Failed to fetch release date (allowing)")
                    .eventCategory("cooldown")
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
            EcsLogger.debug("com.artipie.cooldown")
                .message("No release date found - allowing")
                .eventCategory("cooldown")
                .eventAction("evaluate")
                .eventOutcome("allowed")
                .field("repository.type", request.repoType())
                .field("repository.name", request.repoName())
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .log();
            this.cache.put(request.repoName(), request.artifact(), request.version(), false);
            return CompletableFuture.completedFuture(false);
        }

        // Use per-repo-type minimum allowed age
        final Duration fresh = this.settings.minimumAllowedAgeFor(request.repoType());
        final Instant date = release.get();
        
        // Debug logging to diagnose blocking decisions
        EcsLogger.info("com.artipie.cooldown")
            .message("Evaluating freshness")
            .eventCategory("cooldown")
            .eventAction("freshness_check")
            .field("package.name", request.artifact())
            .field("package.version", request.version())
            .field("release.date", date.toString())
            .field("cooldown.period", fresh.toString())
            .field("release.plus.cooldown", date.plus(fresh).toString())
            .field("request.time", now.toString())
            .field("is.fresh", date.plus(fresh).isAfter(now))
            .log();

        if (date.plus(fresh).isAfter(now)
            && !fresh.isZero() && !fresh.isNegative()) {
            final Instant until = date.plus(fresh);
            EcsLogger.info("com.artipie.cooldown")
                .message("BLOCKING artifact - too fresh (released: " + date.toString() + ", blocked until: " + until.toString() + ")")
                .eventCategory("cooldown")
                .eventAction("evaluate")
                .eventOutcome("blocked")
                .field("package.name", request.artifact())
                .field("package.version", request.version())
                .field("package.release_date", date.toString())
                .log();
            // Create block in database (async)
            return this.createBlockInDatabase(request, inspector, CooldownReason.FRESH_RELEASE, until)
                .thenApply(success -> {
                    // Cache as blocked with dynamic TTL (until block expires)
                    this.cache.putBlocked(request.repoName(), request.artifact(),
                        request.version(), until);
                    return true;
                })
                .exceptionally(error -> {
                    EcsLogger.error("com.artipie.cooldown")
                        .message("Failed to create block (blocking anyway)")
                        .eventCategory("cooldown")
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

        EcsLogger.debug("com.artipie.cooldown")
            .message("ALLOWING artifact - old enough")
            .eventCategory("cooldown")
            .eventAction("evaluate")
            .eventOutcome("allowed")
            .field("package.name", request.artifact())
            .field("package.version", request.version())
            .field("package.release_date", date.toString())
            .field("package.age", Duration.between(date, now).getSeconds())
            .log();
        this.cache.put(request.repoName(), request.artifact(), request.version(), false);
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Create block record in database with dependency checking.
     * @param request Cooldown request
     * @param inspector Inspector for dependencies
     * @param reason Block reason
     * @param blockedUntil Block expiration time
     * @return CompletableFuture<Boolean> (always returns true)
     */
    private CompletableFuture<Boolean> createBlockInDatabase(
        final CooldownRequest request,
        final CooldownInspector inspector,
        final CooldownReason reason,
        final Instant blockedUntil
    ) {
        return CompletableFuture.supplyAsync(() -> {
            final Instant now = request.requestedAt();
            final DbBlockRecord main = this.repository.insertBlock(
                request.repoType(),
                request.repoName(),
                request.artifact(),
                request.version(),
                reason,
                now,
                blockedUntil,
                SYSTEM_ACTOR,
                Optional.empty()
            );
            this.repository.recordAttempt(main.id(), request.requestedBy(), now);
            return main;
        }, this.executor).thenCompose(main -> {
            // Async fetch and insert dependencies (background)
            inspector.dependencies(request.artifact(), request.version())
                .thenAccept(rawDeps -> {
                    final List<CooldownDependency> deps = deduplicateDependencies(
                        rawDeps,
                        request.artifact(),
                        request.version()
                    );
                    if (!deps.isEmpty()) {
                        this.repository.insertDependencies(
                            request.repoType(),
                            request.repoName(),
                            deps,
                            reason,
                            request.requestedAt(),
                            blockedUntil,
                            SYSTEM_ACTOR,
                            main.id()
                        );
                    }
                });
            return CompletableFuture.completedFuture(true);
        });
    }

    /**
     * Process dependencies asynchronously - check freshness and insert blocks.
     * @param request Cooldown request
     * @param main Main block record
     * @param deps Dependency list
     * @param blockedUntil Block expiration
     * @return CompletableFuture with main block record
     */
    private CompletableFuture<DbBlockRecord> processDependencies(
        final CooldownRequest request,
        final DbBlockRecord main,
        final List<CooldownDependency> deps,
        final Instant blockedUntil
    ) {
        final Instant now = request.requestedAt();
        final Duration minAge = this.settings.minimumAllowedAge();
        
        if (minAge.isZero() || minAge.isNegative() || deps.isEmpty()) {
            return CompletableFuture.completedFuture(main);
        }
        
        // Background processing: Insert dependencies asynchronously
        // Don't block the main result - user should not wait for dependency metadata
        CompletableFuture.runAsync(() -> {
            try {
                this.repository.insertDependencies(
                    request.repoType(),
                    request.repoName(),
                    deps,
                    main.reason(),
                    now,
                    blockedUntil,
                    SYSTEM_ACTOR,
                    main.id()
                );
                EcsLogger.debug("com.artipie.cooldown")
                    .message("Inserted " + deps.size() + " dependencies")
                    .eventCategory("cooldown")
                    .eventAction("dependency_insert")
                    .eventOutcome("success")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .log();
            } catch (Exception ex) {
                EcsLogger.warn("com.artipie.cooldown")
                    .message("Failed to insert dependencies")
                    .eventCategory("cooldown")
                    .eventAction("dependency_insert")
                    .eventOutcome("failure")
                    .field("package.name", request.artifact())
                    .field("package.version", request.version())
                    .field("error.message", ex.getMessage())
                    .log();
            }
        }, this.executor);
        
        // Return immediately - don't wait for dependency insertion
        return CompletableFuture.completedFuture(main);
    }

    private void expire(final DbBlockRecord record, final Instant when) {
        this.repository.updateStatus(record.id(), BlockStatus.EXPIRED, Optional.of(when), Optional.empty());
        this.repository.dependenciesOf(record.id()).forEach(
            dep -> this.repository.updateStatus(dep.id(), BlockStatus.EXPIRED, Optional.of(when), Optional.empty())
        );
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
        com.artipie.cooldown.InspectorRegistry.instance()
            .invalidate(repoType, repoName, artifact, version);
    }

    private void unblockAllBlocking(
        final String repoType,
        final String repoName,
        final String actor
    ) {
        final Instant now = Instant.now();
        final List<DbBlockRecord> blocks = this.repository.findActiveForRepo(repoType, repoName);
        blocks.stream()
            .filter(record -> record.status() == BlockStatus.ACTIVE)
            .forEach(record -> this.release(record, actor, now));
        
        // Clear inspector cache (works for all adapters: Docker, NPM, PyPI, etc.)
        com.artipie.cooldown.InspectorRegistry.instance()
            .clearAll(repoType, repoName);
    }

    private void release(final DbBlockRecord record, final String actor, final Instant when) {
        this.repository.updateStatus(
            record.id(),
            BlockStatus.INACTIVE,
            Optional.of(when),
            Optional.of(actor)
        );
        if (record.parentId().isEmpty()) {
            this.repository.dependenciesOf(record.id()).forEach(
                dep -> this.repository.updateStatus(
                    dep.id(),
                    BlockStatus.INACTIVE,
                    Optional.of(when),
                    Optional.of(actor)
                )
            );
        }
    }

    private CooldownBlock toCooldownBlock(
        final DbBlockRecord record,
        final List<DbBlockRecord> dependencies
    ) {
        final List<CooldownDependency> deps = dependencies.stream()
            .map(dep -> new CooldownDependency(dep.artifact(), dep.version()))
            .collect(Collectors.toCollection(ArrayList::new));
        return new CooldownBlock(
            record.repoType(),
            record.repoName(),
            record.artifact(),
            record.version(),
            record.reason(),
            record.blockedAt(),
            record.blockedUntil(),
            deps
        );
    }

    // Version comparison helpers removed as newer-than-cache logic is no longer supported.

    private static List<CooldownDependency> deduplicateDependencies(
        final List<CooldownDependency> deps,
        final String artifact,
        final String version
    ) {
        return deps.stream()
            .filter(dep -> !sameArtifact(artifact, version, dep))
            .collect(
                Collectors.collectingAndThen(
                    Collectors.toMap(
                        dep -> dep.artifact().toLowerCase(Locale.US) + "@" + dep.version(),
                        dep -> dep,
                        (existing, replacement) -> existing
                    ),
                    map -> new ArrayList<>(map.values())
                )
            );
    }

    private static boolean sameArtifact(
        final String artifact,
        final String version,
        final CooldownDependency dep
    ) {
        return artifact.equalsIgnoreCase(dep.artifact()) && version.equals(dep.version());
    }
}
