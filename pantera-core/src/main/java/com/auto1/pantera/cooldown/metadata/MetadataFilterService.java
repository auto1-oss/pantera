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
package com.auto1.pantera.cooldown.metadata;

import com.auto1.pantera.cooldown.cache.CooldownCache;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.config.CooldownSettings;
import com.auto1.pantera.cooldown.metrics.CooldownMetrics;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Locale;

import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

/**
 * Implementation of {@link CooldownMetadataService}.
 * Filters package metadata to remove blocked versions before serving to clients.
 *
 * <p>Performance characteristics:</p>
 * <ul>
 *   <li>Cache hit: &lt; 1ms (L1 Caffeine cache)</li>
 *   <li>Cache miss: 20-200ms depending on metadata size and version count</li>
 *   <li>Bounded evaluation: Only evaluates latest N versions (configurable)</li>
 * </ul>
 *
 * @since 1.0
 */
public final class MetadataFilterService implements CooldownMetadataService {

    /**
     * Default maximum versions to evaluate for cooldown.
     * Older versions are implicitly allowed.
     */
    private static final int DEFAULT_MAX_VERSIONS = 50;

    /**
     * Default max TTL for cache entries when no versions are blocked.
     * Since release dates don't change, we can cache for a long time.
     */
    private static final Duration DEFAULT_MAX_TTL = Duration.ofHours(24);

    /**
     * Cooldown service for block decisions.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown settings.
     */
    private final CooldownSettings settings;

    /**
     * Per-version cooldown cache.
     */
    private final CooldownCache cooldownCache;

    /**
     * Filtered metadata cache.
     */
    private final FilteredMetadataCache metadataCache;

    /**
     * Executor for async operations (metadata parse, filter, rewrite).
     */
    private final Executor executor;

    /**
     * Maximum versions to evaluate.
     */
    private final int maxVersionsToEvaluate;

    /**
     * Version comparators by repo type.
     */
    private final Map<String, Comparator<String>> versionComparators;

    /**
     * Maximum TTL for cache entries.
     */
    private final Duration maxTtl;

    /**
     * Constructor with defaults.
     *
     * @param cooldown Cooldown service
     * @param settings Cooldown settings
     * @param cooldownCache Per-version cooldown cache
     */
    public MetadataFilterService(
        final CooldownService cooldown,
        final CooldownSettings settings,
        final CooldownCache cooldownCache
    ) {
        this(
            cooldown,
            settings,
            cooldownCache,
            new FilteredMetadataCache(),
            ForkJoinPool.commonPool(),
            DEFAULT_MAX_VERSIONS
        );
    }

    /**
     * Full constructor.
     *
     * @param cooldown Cooldown service
     * @param settings Cooldown settings
     * @param cooldownCache Per-version cooldown cache
     * @param metadataCache Filtered metadata cache
     * @param executor Executor for async operations
     * @param maxVersionsToEvaluate Maximum versions to evaluate
     */
    public MetadataFilterService(
        final CooldownService cooldown,
        final CooldownSettings settings,
        final CooldownCache cooldownCache,
        final FilteredMetadataCache metadataCache,
        final Executor executor,
        final int maxVersionsToEvaluate
    ) {
        this.cooldown = Objects.requireNonNull(cooldown);
        this.settings = Objects.requireNonNull(settings);
        this.cooldownCache = Objects.requireNonNull(cooldownCache);
        this.metadataCache = Objects.requireNonNull(metadataCache);
        this.executor = com.auto1.pantera.http.context.ContextualExecutor
            .contextualize(Objects.requireNonNull(executor));
        this.maxVersionsToEvaluate = maxVersionsToEvaluate;
        this.versionComparators = Map.of(
            "npm", VersionComparators.semver(),
            "composer", VersionComparators.semver(),
            "maven", VersionComparators.maven(),
            "gradle", VersionComparators.maven(),
            "pypi", VersionComparators.semver(),
            "go", VersionComparators.lexical()
        );
        this.maxTtl = DEFAULT_MAX_TTL;
    }

    @Override
    public <T> CompletableFuture<byte[]> filterMetadata(
        final String repoType,
        final String repoName,
        final String packageName,
        final byte[] rawMetadata,
        final MetadataParser<T> parser,
        final MetadataFilter<T> filter,
        final MetadataRewriter<T> rewriter
    ) {
        // Check if cooldown is enabled for this repo identity.
        // Precedence: per-repo-name override → per-repo-type override → global.
        // Mirrors JdbcCooldownService.effectiveEnabled so the metadata-filter
        // pre-selection cannot disagree with the request-time evaluator —
        // otherwise versions aged between the global cutoff and a laxer
        // per-type cutoff would silently pass through.
        if (!this.settings.effectiveEnabled(repoType, repoName)) {
            EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                .message("Cooldown disabled for repo type, returning raw metadata")
                .eventCategory("database")
                .eventAction("metadata_filter")
                .field("repository.type", repoType)
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();
            return CompletableFuture.completedFuture(rawMetadata);
        }

        final long startTime = System.nanoTime();

        // Try cache first
        return this.metadataCache.get(
            repoType,
            repoName,
            packageName,
            () -> this.computeFilteredMetadata(
                repoType, repoName, packageName, rawMetadata,
                parser, filter, rewriter, startTime
            )
        );
    }

    /**
     * Compute filtered metadata (called on cache miss).
     * Returns CacheEntry with dynamic TTL based on earliest blockedUntil.
     */
    private <T> CompletableFuture<FilteredMetadataCache.CacheEntry> computeFilteredMetadata(
        final String repoType,
        final String repoName,
        final String packageName,
        final byte[] rawMetadata,
        final MetadataParser<T> parser,
        final MetadataFilter<T> filter,
        final MetadataRewriter<T> rewriter,
        final long startTime
    ) {
        return CompletableFuture.supplyAsync(() -> {
            // Step 1: Parse metadata
            final T parsed = parser.parse(rawMetadata);
            final List<String> allVersions = parser.extractVersions(parsed);

            if (allVersions.isEmpty()) {
                EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                    .message("No versions in metadata")
                    .eventCategory("database")
                    .eventAction("metadata_filter")
                    .field("repository.type", repoType)
                    .field("package.name", packageName)
                    .field("log.source", "application")
                    .log();
                // No versions - cache with max TTL
                return FilteredMetadataCache.CacheEntry.noBlockedVersions(rawMetadata, this.maxTtl);
            }

            // Step 2: Get release dates from metadata (if available)
            // Prefer the new MetadataParser.extractReleaseDates() SPI; fall back
            // to the older ReleaseDateProvider interface for backward compat;
            // then backfill any still-missing versions from the publish-date
            // registry. The registry-backfill is what restores the Maven /
            // Gradle cooldown filter — artifact-level maven-metadata.xml has
            // no per-version timestamps, so extracted is structurally empty
            // for those formats and shouldBlockNewArtifact(req, empty) would
            // otherwise fail-open ("no release date — allowing").
            final Map<String, Instant> extracted = parser.extractReleaseDates(parsed);
            final Map<String, Instant> releaseDates = this.resolveReleaseDates(
                repoType, packageName, parser, parsed, extracted, allVersions
            );

            // Step 2c: Pre-warm CooldownCache L1 with release dates from metadata.
            // Versions older than the cooldown period are guaranteed allowed (false).
            if (!releaseDates.isEmpty()) {
                this.preWarmCooldownCache(repoType, repoName, packageName, releaseDates);
            }

            // Step 3: Select versions to evaluate based on RELEASE DATE, not semver
            // Only versions released within the cooldown period could possibly be blocked.
            // Use the per-repo-identity effective duration so a per-name or per-type
            // override that loosens (or tightens) the window is honoured by the
            // pre-selection cutoff. SNAPSHOT-stricter knobs are applied
            // per-version downstream by evaluateWithKnownDate; here we want the
            // release-mode cutoff which is the laxer of the two for SNAPSHOTs
            // and the only cutoff for releases.
            final Duration cooldownPeriod =
                this.settings.effectiveMinimumAllowedAge(repoType, repoName);
            final Instant cutoffTime = Instant.now().minus(cooldownPeriod);
            
            final List<String> versionsToEvaluate;
            final List<String> sortedVersions;
            
            if (!releaseDates.isEmpty()) {
                // RELEASE DATE BASED: Sort by release date, then binary search for cutoff
                // O(n log n) sort + O(log n) binary search - more efficient than O(n) filter
                sortedVersions = new ArrayList<>(allVersions);
                sortedVersions.sort((v1, v2) -> {
                    final Instant d1 = releaseDates.getOrDefault(v1, Instant.EPOCH);
                    final Instant d2 = releaseDates.getOrDefault(v2, Instant.EPOCH);
                    return d2.compareTo(d1); // Newest first (descending by date)
                });
                
                // Binary search: find first version older than cutoff
                // Since sorted newest-first, we find first index where releaseDate <= cutoffTime
                int cutoffIndex = Collections.binarySearch(
                    sortedVersions,
                    null, // dummy search key
                    (v1, v2) -> {
                        // v1 is from list, v2 is our dummy (null)
                        // We want to find where releaseDate crosses cutoffTime
                        if (v1 == null) {
                            return 0; // dummy comparison
                        }
                        final Instant d1 = releaseDates.getOrDefault(v1, Instant.EPOCH);
                        // Return negative if d1 > cutoff (keep searching right)
                        // Return positive if d1 <= cutoff (found boundary)
                        return d1.isAfter(cutoffTime) ? -1 : 1;
                    }
                );
                // binarySearch returns -(insertionPoint + 1) when not found
                // insertionPoint is where cutoff would be inserted to maintain order
                if (cutoffIndex < 0) {
                    cutoffIndex = -(cutoffIndex + 1);
                }
                
                // Take all versions from index 0 to cutoffIndex (exclusive) - these are newer than cutoff
                versionsToEvaluate = cutoffIndex > 0 
                    ? sortedVersions.subList(0, cutoffIndex)
                    : Collections.emptyList();
            } else {
                // FALLBACK: No release dates available, use semver-based limit
                // This is less accurate but better than nothing
                final Comparator<String> comparator = this.versionComparators
                    .getOrDefault(repoType.toLowerCase(Locale.ROOT), VersionComparators.semver());
                sortedVersions = new ArrayList<>(allVersions);
                sortedVersions.sort(comparator.reversed()); // Newest first by semver
                
                versionsToEvaluate = sortedVersions.stream()
                    .limit(this.maxVersionsToEvaluate)
                    .collect(Collectors.toList());
            }

            EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                .message(String.format(
                    "Evaluating cooldown for versions: %d total, %d to evaluate",
                    allVersions.size(), versionsToEvaluate.size()))
                .eventCategory("database")
                .eventAction("metadata_filter")
                .field("repository.type", repoType)
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();

            return new FilterContext<>(
                repoType, repoName, packageName, parsed,
                allVersions, sortedVersions, versionsToEvaluate,
                parser, filter, rewriter, releaseDates, startTime
            );
        }, this.executor).thenCompose(ctx -> {
            if (ctx instanceof FilteredMetadataCache.CacheEntry) {
                return CompletableFuture.completedFuture((FilteredMetadataCache.CacheEntry) ctx);
            }
            @SuppressWarnings("unchecked")
            final FilterContext<T> context = (FilterContext<T>) ctx;
            return this.evaluateAndFilter(context);
        });
    }

    /**
     * Build the effective {@code releaseDates} map for the version-evaluation
     * loop. Sources are consulted in this order, each filling in dates not
     * already known by an earlier source:
     * <ol>
     *   <li>Inline dates extracted by the parser ({@code extracted}).</li>
     *   <li>Legacy {@link ReleaseDateProvider} SPI, when the parser implements it.</li>
     *   <li>{@link com.auto1.pantera.publishdate.PublishDateRegistry} — L1+L2
     *       lookup for versions still without a date. This is what restores
     *       Maven/Gradle cooldown semantics; their artifact-level metadata
     *       carries no per-version timestamps so {@code extracted} is empty.</li>
     * </ol>
     * The whole-batch registry phase is gated by a 2-second {@code allOf}
     * timeout (NOT a per-version wall) so a slow registry can't reintroduce
     * the 1.7-second-per-version perf bug that {@code dbdde1736} fixed.
     * Versions whose date is still unknown after all three sources fail-open
     * (evaluated with {@code Optional.empty()}, allowed).
     *
     * @param repoType Repository type
     * @param packageName Package / artifact name
     * @param parser Metadata parser (may also implement {@link ReleaseDateProvider})
     * @param parsed Parsed metadata
     * @param extracted Inline release dates from the parser
     * @param allVersions Every version in the parsed metadata
     * @param <T> Parsed metadata type
     * @return Effective release-date map for evaluation
     */
    private <T> Map<String, Instant> resolveReleaseDates(
        final String repoType,
        final String packageName,
        final MetadataParser<T> parser,
        final T parsed,
        final Map<String, Instant> extracted,
        final List<String> allVersions
    ) {
        final java.util.Map<String, java.time.Instant> mutable;
        if (extracted.isEmpty()) {
            mutable = new java.util.concurrent.ConcurrentHashMap<>();
        } else {
            mutable = new java.util.concurrent.ConcurrentHashMap<>(extracted);
        }
        if (extracted.isEmpty() && parser instanceof ReleaseDateProvider) {
            @SuppressWarnings("unchecked")
            final ReleaseDateProvider<T> provider = (ReleaseDateProvider<T>) parser;
            for (final Map.Entry<String, Instant> entry : provider.releaseDates(parsed).entrySet()) {
                if (entry.getValue() != null) {
                    mutable.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.backfillFromRegistry(repoType, packageName, allVersions, mutable);
        return java.util.Collections.unmodifiableMap(mutable);
    }

    /**
     * Fill {@code mutable} with publish dates resolved through
     * {@link com.auto1.pantera.publishdate.PublishDateRegistry} for every
     * version that doesn't already have a date. Bounded by
     * {@link #maxVersionsToEvaluate} (older versions can't be inside any
     * realistic cooldown window so a date for them is wasted I/O) and capped
     * by a 2-second whole-batch {@code allOf} timeout. Lookup uses
     * {@link com.auto1.pantera.publishdate.PublishDateRegistry.Mode#NETWORK_FALLBACK}
     * because the registry's L1/L2 caches plus the gradle-proxy → maven-proxy
     * alias fallback keep steady-state cost negligible; the timeout is the
     * cold-cache safety net.
     */
    private void backfillFromRegistry(
        final String repoType,
        final String packageName,
        final List<String> allVersions,
        final Map<String, Instant> mutable
    ) {
        final com.auto1.pantera.publishdate.PublishDateRegistry registry =
            com.auto1.pantera.publishdate.PublishDateRegistries.instance();
        if (registry == null) {
            return;
        }
        final List<String> missing = new ArrayList<>();
        for (final String version : allVersions) {
            if (!mutable.containsKey(version)) {
                missing.add(version);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        // Sort newest-first by version comparator before truncating to the
        // top-N. The {@code allVersions} list reflects document order, which
        // for Maven's maven-metadata.xml is ASCENDING (oldest first). Without
        // this reversal the cap fetches dates for the OLDEST 50 versions —
        // the opposite of what cooldown needs, since the newest releases are
        // the only ones plausibly inside the cooldown window. Comparator is
        // keyed by repo type; unknown types fall back to semver ordering.
        final Comparator<String> versionCmp = this.versionComparators
            .getOrDefault(
                repoType.toLowerCase(Locale.ROOT),
                VersionComparators.semver()
            );
        missing.sort(versionCmp.reversed());
        final int cap = Math.min(missing.size(), this.maxVersionsToEvaluate);
        final List<CompletableFuture<Void>> lookups = new ArrayList<>(cap);
        for (int idx = 0; idx < cap; idx++) {
            final String version = missing.get(idx);
            lookups.add(
                registry.publishDate(
                    repoType, packageName, version,
                    com.auto1.pantera.publishdate.PublishDateRegistry.Mode.NETWORK_FALLBACK
                )
                .thenAccept(opt -> opt.ifPresent(instant -> mutable.put(version, instant)))
                .exceptionally(err -> null)
            );
        }
        try {
            CompletableFuture.allOf(
                lookups.toArray(new CompletableFuture[0])
            ).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final java.util.concurrent.TimeoutException ex) {
            EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                .message("Publish-date registry backfill timed out; proceeding with partial dates")
                .eventCategory("database")
                .eventAction("metadata_filter")
                .field("repository.type", repoType)
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (final java.util.concurrent.ExecutionException ex) {
            // Individual lookups already swallow errors via .exceptionally; this
            // path is unreachable in practice but kept for completeness.
            EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                .message("Publish-date registry backfill failed: " + ex.getMessage())
                .eventCategory("database")
                .eventAction("metadata_filter")
                .field("repository.type", repoType)
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Evaluate cooldown for versions and filter metadata.
     * Returns CacheEntry with TTL based on earliest blockedUntil.
     * Versions are evaluated via the cooldown service with the
     * inline release dates from the upstream packument — no inspector
     * network fetch on the hot path.
     */
    private <T> CompletableFuture<FilteredMetadataCache.CacheEntry> evaluateAndFilter(final FilterContext<T> ctx) {
        // Step 4: Evaluate cooldown for each version with known release date (no I/O)
        final List<CompletableFuture<VersionBlockResult>> futures = ctx.versionsToEvaluate.stream()
            .limit(this.maxVersionsToEvaluate)
            .map(version -> this.evaluateVersion(
                ctx.repoType, ctx.repoName, ctx.packageName, version, ctx.releaseDates
            ))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                // Step 5: Collect blocked versions and find earliest blockedUntil
                final Set<String> blockedVersions = new HashSet<>();
                Instant earliestBlockedUntil = null;
                for (final CompletableFuture<VersionBlockResult> future : futures) {
                    final VersionBlockResult result = future.join();
                    if (result.blocked) {
                        blockedVersions.add(result.version);
                        // Track earliest blockedUntil for cache TTL
                        if (result.blockedUntil != null
                            && (earliestBlockedUntil == null || result.blockedUntil.isBefore(earliestBlockedUntil))) {
                            earliestBlockedUntil = result.blockedUntil;
                        }
                    }
                }

                EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                    .message(String.format(
                        "Cooldown evaluation complete: %d versions blocked", blockedVersions.size()))
                    .eventCategory("database")
                    .eventAction("metadata_filter")
                    .field("repository.type", ctx.repoType)
                    .field("package.name", ctx.packageName)
                    .field("log.source", "application")
                    .log();

                // Note: Blocked versions gauge is updated by JdbcCooldownService on block/unblock
                // We don't increment counters here as that would count evaluations, not actual blocks

                // Step 6: Check if all versions are blocked
                if (blockedVersions.size() == ctx.allVersions.size()) {
                    // Mark as all-blocked in database and update gauge metric
                    this.cooldown.markAllBlocked(ctx.repoType, ctx.repoName, ctx.packageName);
                    throw new AllVersionsBlockedException(ctx.packageName, blockedVersions);
                }

                // Step 7: Filter metadata
                T filtered = ctx.filter.filter(ctx.parsed, blockedVersions);

                // Step 8: Recompute latest/release whenever anything is blocked.
                // Pre-Phase-D the rewrite only fired when <latest> itself was
                // blocked — leaving <release> pointing at a blocked stable
                // version even though <latest> was a surviving SNAPSHOT, so
                // Gradle's latest.release resolution would pick a version it
                // could not subsequently download. findLatestByReleaseDate
                // returns the latest non-blocked version regardless of
                // whether currentLatest itself was blocked.
                final Optional<String> currentLatest = ctx.parser.getLatestVersion(ctx.parsed);
                if (!blockedVersions.isEmpty()) {
                    final Optional<String> newLatest = this.findLatestByReleaseDate(
                        ctx.parser, ctx.parsed, ctx.sortedVersions, blockedVersions
                    );
                    if (newLatest.isPresent()) {
                        filtered = ctx.filter.updateLatest(filtered, newLatest.get());
                        EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                            .message(String.format(
                                "Recomputed latest/release version: %s -> %s",
                                currentLatest.orElse("(none)"), newLatest.get()))
                            .eventCategory("database")
                            .eventAction("metadata_filter")
                            .field("package.name", ctx.packageName)
                            .field("log.source", "application")
                            .log();
                    }
                }

                // Step 9: Rewrite metadata
                final byte[] resultBytes = ctx.rewriter.rewrite(filtered);

                // Log performance
                final long durationMs = (System.nanoTime() - ctx.startTime) / 1_000_000;
                EcsLogger.info("com.auto1.pantera.cooldown.metadata")
                    .message(String.format(
                        "Metadata filtering complete: %d total versions, %d blocked",
                        ctx.allVersions.size(), blockedVersions.size()))
                    .eventCategory("database")
                    .eventAction("metadata_filter")
                    .eventOutcome("success")
                    .field("repository.type", ctx.repoType)
                    .field("package.name", ctx.packageName)
                    .field("event.duration", durationMs)
                    .field("log.source", "application")
                    .log();

                // Record metrics via CooldownMetrics
                if (CooldownMetrics.isAvailable()) {
                    CooldownMetrics.getInstance().recordFilterDuration(
                        ctx.repoType, durationMs, ctx.allVersions.size(), blockedVersions.size()
                    );
                }

                // Step 10: Create cache entry with dynamic TTL
                // TTL = min(blockedUntil) - now, or max TTL if no blocked versions
                if (earliestBlockedUntil != null) {
                    return FilteredMetadataCache.CacheEntry.withBlockedVersions(
                        resultBytes, earliestBlockedUntil, this.maxTtl
                    );
                }
                return FilteredMetadataCache.CacheEntry.noBlockedVersions(resultBytes, this.maxTtl);
            });
    }

    /**
     * Evaluate cooldown for a single version using the release date already
     * extracted from the upstream packument. Skips the inspector network
     * fetch entirely; the cooldown service decides from cache + DB plus
     * the supplied date.
     */
    private CompletableFuture<VersionBlockResult> evaluateVersion(
        final String repoType,
        final String repoName,
        final String packageName,
        final String version,
        final Map<String, Instant> releaseDates
    ) {
        // Get real user from MDC (set by auth middleware), fallback to "metadata-filter"
        String requester = MDC.get("user.name");
        if (requester == null || requester.isEmpty()) {
            requester = "metadata-filter";
        }
        final CooldownRequest request = new CooldownRequest(
            repoType,
            repoName,
            packageName,
            version,
            requester,
            Instant.now()
        );
        final Optional<Instant> knownDate = Optional.ofNullable(releaseDates.get(version));
        return this.cooldown.evaluateWithKnownDate(request, knownDate)
            .thenApply(result -> {
                if (result.blocked()) {
                    final Instant blockedUntil = result.block()
                        .map(block -> block.blockedUntil())
                        .orElse(null);
                    return new VersionBlockResult(version, true, blockedUntil);
                }
                return new VersionBlockResult(version, false, null);
            });
    }

    /**
     * Pre-warm CooldownCache L1 with release dates extracted from metadata.
     * Versions whose release date is older than the cooldown period are
     * guaranteed to be allowed (not blocked due to freshness), so we can
     * populate the L1 cache with {@code false} (allowed) immediately.
     * This avoids a DB/Valkey round-trip on the hot path for the majority
     * of versions that are well past the cooldown window.
     *
     * <p>Uses {@link CooldownSettings#effectiveMinimumAllowedAge(String, String)}
     * so the per-repo-name / per-repo-type precedence chain is honoured: a
     * laxer override must NOT pre-mark in-window versions as allowed, and a
     * stricter override must NOT pre-mark borderline versions as allowed
     * before the request-time evaluator gets to see them.
     *
     * @param repoType Repository type (for precedence lookup)
     * @param repoName Repository name
     * @param packageName Package name
     * @param releaseDates Map of version to release timestamp
     */
    private void preWarmCooldownCache(
        final String repoType,
        final String repoName,
        final String packageName,
        final Map<String, Instant> releaseDates
    ) {
        final Instant cutoff = Instant.now().minus(
            this.settings.effectiveMinimumAllowedAge(repoType, repoName)
        );
        int warmed = 0;
        for (final Map.Entry<String, Instant> entry : releaseDates.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                // Version is older than cooldown period -- guaranteed allowed
                this.cooldownCache.put(repoName, packageName, entry.getKey(), false);
                warmed++;
            }
        }
        if (warmed > 0) {
            EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
                .message(String.format(
                    "Pre-warmed CooldownCache L1 with %d allowed versions from metadata", warmed))
                .eventCategory("database")
                .eventAction("cache_prewarm")
                .field("repository.name", repoName)
                .field("package.name", packageName)
                .field("log.source", "application")
                .log();
        }
    }

    @Override
    public void invalidate(
        final String repoType,
        final String repoName,
        final String packageName
    ) {
        this.metadataCache.invalidate(repoType, repoName, packageName);
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordInvalidation(repoType, "unblock");
        }
        EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
            .message("Invalidated metadata cache")
            .eventCategory("database")
            .eventAction("cache_invalidate")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .field("log.source", "application")
            .log();
    }

    @Override
    public void invalidateAll(final String repoType, final String repoName) {
        this.metadataCache.invalidateAll(repoType, repoName);
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordInvalidation(repoType, "unblock_all");
        }
        EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
            .message("Invalidated all metadata cache for repository")
            .eventCategory("database")
            .eventAction("cache_invalidate")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("log.source", "application")
            .log();
    }

    @Override
    public void clearAll() {
        this.metadataCache.clear();
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordInvalidation("*", "policy_change");
        }
        EcsLogger.debug("com.auto1.pantera.cooldown.metadata")
            .message("Cleared all metadata caches (policy change)")
            .eventCategory("database")
            .eventAction("cache_clear_all")
            .field("log.source", "application")
            .log();
    }

    @Override
    public String stats() {
        return this.metadataCache.stats();
    }

    /**
     * Find the most recent unblocked STABLE version by release date.
     * This respects package author's intent - if they set a lower semver version as latest
     * (e.g., deprecating a major version branch), we fallback to the next most recently
     * released STABLE version, not a prerelease.
     *
     * @param parser Metadata parser (must implement ReleaseDateProvider)
     * @param parsed Parsed metadata
     * @param allVersions All available versions (sorted by semver desc)
     * @param blockedVersions Set of blocked versions to exclude
     * @param <T> Metadata type
     * @return Most recent unblocked stable version by release date, or empty if none found
     */
    @SuppressWarnings("unchecked")
    private <T> Optional<String> findLatestByReleaseDate(
        final MetadataParser<T> parser,
        final T parsed,
        final List<String> allVersions,
        final Set<String> blockedVersions
    ) {
        // Get release dates if parser supports it
        if (!(parser instanceof ReleaseDateProvider)) {
            // Fallback to first unblocked STABLE version
            return allVersions.stream()
                .filter(ver -> !blockedVersions.contains(ver))
                .filter(ver -> !isPrerelease(ver))
                .findFirst()
                .or(() -> allVersions.stream()
                    .filter(ver -> !blockedVersions.contains(ver))
                    .findFirst()); // If no stable, use any unblocked
        }
        
        final ReleaseDateProvider<T> dateProvider = (ReleaseDateProvider<T>) parser;
        final Map<String, Instant> releaseDates = dateProvider.releaseDates(parsed);
        
        if (releaseDates.isEmpty()) {
            // No release dates available - fallback to first unblocked STABLE version
            return allVersions.stream()
                .filter(ver -> !blockedVersions.contains(ver))
                .filter(ver -> !isPrerelease(ver))
                .findFirst()
                .or(() -> allVersions.stream()
                    .filter(ver -> !blockedVersions.contains(ver))
                    .findFirst()); // If no stable, use any unblocked
        }
        
        // Sort unblocked STABLE versions by release date (most recent first)
        final Optional<String> stableLatest = allVersions.stream()
            .filter(ver -> !blockedVersions.contains(ver))
            .filter(ver -> !isPrerelease(ver))
            .filter(ver -> releaseDates.containsKey(ver))
            .sorted((v1, v2) -> {
                final Instant d1 = releaseDates.get(v1);
                final Instant d2 = releaseDates.get(v2);
                return d2.compareTo(d1); // Descending (most recent first)
            })
            .findFirst();
        
        if (stableLatest.isPresent()) {
            return stableLatest;
        }
        
        // No stable versions - fallback to any unblocked version by release date
        return allVersions.stream()
            .filter(ver -> !blockedVersions.contains(ver))
            .filter(ver -> releaseDates.containsKey(ver))
            .sorted((v1, v2) -> {
                final Instant d1 = releaseDates.get(v1);
                final Instant d2 = releaseDates.get(v2);
                return d2.compareTo(d1);
            })
            .findFirst();
    }
    
    /**
     * Known prerelease qualifier tokens. Match is case-insensitive and on a
     * full token (delimited by {@code -}, {@code .}, or {@code +}), NOT a
     * substring — otherwise classifier-style suffixes such as Guava's
     * {@code -jre}/{@code -android} or substrings inside legitimate words
     * (the {@code rc} in {@code archived}, the {@code dev} in {@code
     * developer}, etc.) are wrongly flagged and the cooldown service falls
     * back to the wrong "stable" version.
     */
    private static final Set<String> PRERELEASE_QUALIFIERS = Set.of(
        "alpha", "beta", "rc", "milestone", "snapshot",
        "canary", "next", "dev", "preview", "pre", "cr", "ea"
    );

    /**
     * Maven milestone shorthand: {@code 1.0-M3}, {@code 2.0-m1}. Requires at
     * least one digit after the {@code m} so we don't snag classifier
     * tokens that simply start with {@code m} (e.g. {@code -macos}).
     */
    private static final java.util.regex.Pattern MAVEN_MILESTONE =
        java.util.regex.Pattern.compile("(?i)m\\d+");

    /**
     * Check if a version is a prerelease (alpha, beta, rc, snapshot, etc.).
     *
     * <p>Tokenises on {@code -}, {@code .}, and {@code +} (the SemVer / Maven
     * qualifier separators) and checks each token against {@link
     * #PRERELEASE_QUALIFIERS} or the milestone shorthand. The first token —
     * which is always the version core (e.g. {@code 33.5.0}, {@code r09}) —
     * is skipped so a leading numeric or {@code rN} segment cannot be
     * mistaken for a qualifier.</p>
     *
     * @param version Version string
     * @return {@code true} if any post-core token is a known prerelease
     *     qualifier; {@code false} for stable, classifier-suffixed, or
     *     unknown formats (treat-as-stable is the safer default for the
     *     "pick the new latest" path — a misclassified prerelease at worst
     *     surfaces a slightly newer-than-expected version, while a
     *     misclassified classifier collapses {@code latest} to a decade-old
     *     release as in the Guava 33.x → r09 regression).
     */
    static boolean isPrerelease(final String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        final String[] tokens = version.split("[-.+]");
        for (int idx = 1; idx < tokens.length; idx++) {
            final String token = tokens[idx];
            if (token.isEmpty()) {
                continue;
            }
            final String lower = token.toLowerCase(java.util.Locale.ROOT);
            // Strip a trailing numeric run (rc1 → rc, beta02 → beta) so
            // numbered qualifiers still match the keyword set.
            final String stripped = lower.replaceAll("\\d+$", "");
            if (PRERELEASE_QUALIFIERS.contains(stripped)) {
                return true;
            }
            if (MAVEN_MILESTONE.matcher(token).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Context for filtering operation.
     */
    private static final class FilterContext<T> {
        final String repoType;
        final String repoName;
        final String packageName;
        final T parsed;
        final List<String> allVersions;
        final List<String> sortedVersions;
        final List<String> versionsToEvaluate;
        final MetadataParser<T> parser;
        final MetadataFilter<T> filter;
        final MetadataRewriter<T> rewriter;
        final Map<String, Instant> releaseDates;
        final long startTime;

        FilterContext(
            final String repoType,
            final String repoName,
            final String packageName,
            final T parsed,
            final List<String> allVersions,
            final List<String> sortedVersions,
            final List<String> versionsToEvaluate,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter,
            final Map<String, Instant> releaseDates,
            final long startTime
        ) {
            this.repoType = repoType;
            this.repoName = repoName;
            this.packageName = packageName;
            this.parsed = parsed;
            this.allVersions = allVersions;
            this.sortedVersions = sortedVersions;
            this.versionsToEvaluate = versionsToEvaluate;
            this.parser = parser;
            this.filter = filter;
            this.rewriter = rewriter;
            this.releaseDates = releaseDates;
            this.startTime = startTime;
        }
    }

    /**
     * Result of version block evaluation.
     * Includes blockedUntil timestamp for cache TTL calculation.
     */
    private static final class VersionBlockResult {
        final String version;
        final boolean blocked;
        final Instant blockedUntil;

        VersionBlockResult(final String version, final boolean blocked, final Instant blockedUntil) {
            this.version = version;
            this.blocked = blocked;
            this.blockedUntil = blockedUntil;
        }
    }
}
