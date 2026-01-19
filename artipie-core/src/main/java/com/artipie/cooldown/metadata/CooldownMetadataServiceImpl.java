/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cooldown.metadata;

import com.artipie.cooldown.CooldownCache;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownService;
import com.artipie.cooldown.CooldownSettings;
import com.artipie.cooldown.metrics.CooldownMetrics;
import com.artipie.http.log.EcsLogger;
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
public final class CooldownMetadataServiceImpl implements CooldownMetadataService {

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
     * Executor for async operations.
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
    public CooldownMetadataServiceImpl(
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
    public CooldownMetadataServiceImpl(
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
        this.executor = Objects.requireNonNull(executor);
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
        final MetadataRewriter<T> rewriter,
        final Optional<CooldownInspector> inspectorOpt
    ) {
        // Check if cooldown is enabled for this repo type
        if (!this.settings.enabledFor(repoType)) {
            EcsLogger.debug("com.artipie.cooldown.metadata")
                .message("Cooldown disabled for repo type, returning raw metadata")
                .eventCategory("cooldown")
                .eventAction("metadata_filter")
                .field("repository.type", repoType)
                .field("package.name", packageName)
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
                parser, filter, rewriter, inspectorOpt, startTime
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
        final Optional<CooldownInspector> inspectorOpt,
        final long startTime
    ) {
        return CompletableFuture.supplyAsync(() -> {
            // Step 1: Parse metadata
            final T parsed = parser.parse(rawMetadata);
            final List<String> allVersions = parser.extractVersions(parsed);

            if (allVersions.isEmpty()) {
                EcsLogger.debug("com.artipie.cooldown.metadata")
                    .message("No versions in metadata")
                    .eventCategory("cooldown")
                    .eventAction("metadata_filter")
                    .field("repository.type", repoType)
                    .field("package.name", packageName)
                    .log();
                // No versions - cache with max TTL
                return FilteredMetadataCache.CacheEntry.noBlockedVersions(rawMetadata, this.maxTtl);
            }

            // Step 2: Get release dates from metadata (if available)
            final Map<String, Instant> releaseDates;
            if (parser instanceof ReleaseDateProvider) {
                @SuppressWarnings("unchecked")
                final ReleaseDateProvider<T> provider = (ReleaseDateProvider<T>) parser;
                releaseDates = provider.releaseDates(parsed);
            } else {
                releaseDates = Collections.emptyMap();
            }

            // Step 2b: Preload release dates into inspector for later use
            this.preloadReleaseDates(parser, parsed, inspectorOpt);

            // Step 3: Select versions to evaluate based on RELEASE DATE, not semver
            // Only versions released within the cooldown period could possibly be blocked
            final Duration cooldownPeriod = this.settings.minimumAllowedAge();
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
                    .getOrDefault(repoType.toLowerCase(), VersionComparators.semver());
                sortedVersions = new ArrayList<>(allVersions);
                sortedVersions.sort(comparator.reversed()); // Newest first by semver
                
                versionsToEvaluate = sortedVersions.stream()
                    .limit(this.maxVersionsToEvaluate)
                    .collect(Collectors.toList());
            }

            EcsLogger.debug("com.artipie.cooldown.metadata")
                .message("Evaluating cooldown for versions")
                .eventCategory("cooldown")
                .eventAction("metadata_filter")
                .field("repository.type", repoType)
                .field("package.name", packageName)
                .field("versions.total", allVersions.size())
                .field("versions.evaluating", versionsToEvaluate.size())
                .log();

            return new FilterContext<>(
                repoType, repoName, packageName, parsed,
                allVersions, sortedVersions, versionsToEvaluate,
                parser, filter, rewriter, inspectorOpt, startTime
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
     * Evaluate cooldown for versions and filter metadata.
     * Returns CacheEntry with TTL based on earliest blockedUntil.
     */
    private <T> CompletableFuture<FilteredMetadataCache.CacheEntry> evaluateAndFilter(final FilterContext<T> ctx) {
        // Step 4: Evaluate cooldown for each version in parallel
        final List<CompletableFuture<VersionBlockResult>> futures = ctx.versionsToEvaluate.stream()
            .map(version -> this.evaluateVersion(
                ctx.repoType, ctx.repoName, ctx.packageName, version, ctx.inspectorOpt
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
                        if (result.blockedUntil != null) {
                            if (earliestBlockedUntil == null || result.blockedUntil.isBefore(earliestBlockedUntil)) {
                                earliestBlockedUntil = result.blockedUntil;
                            }
                        }
                    }
                }

                EcsLogger.debug("com.artipie.cooldown.metadata")
                    .message("Cooldown evaluation complete")
                    .eventCategory("cooldown")
                    .eventAction("metadata_filter")
                    .field("repository.type", ctx.repoType)
                    .field("package.name", ctx.packageName)
                    .field("versions.blocked", blockedVersions.size())
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

                // Step 8: Update latest if needed
                final Optional<String> currentLatest = ctx.parser.getLatestVersion(ctx.parsed);
                if (currentLatest.isPresent() && blockedVersions.contains(currentLatest.get())) {
                    // Find new latest by RELEASE DATE (most recent unblocked version)
                    // This respects the package author's intent - if they set a lower version as latest,
                    // we should fallback to the next most recently released version, not the highest semver
                    // Pass sortedVersions (sorted by semver desc) for fallback when no release dates
                    final Optional<String> newLatest = this.findLatestByReleaseDate(
                        ctx.parser, ctx.parsed, ctx.sortedVersions, blockedVersions
                    );
                    if (newLatest.isPresent()) {
                        filtered = ctx.filter.updateLatest(filtered, newLatest.get());
                        EcsLogger.debug("com.artipie.cooldown.metadata")
                            .message("Updated latest version (by release date)")
                            .eventCategory("cooldown")
                            .eventAction("metadata_filter")
                            .field("package.name", ctx.packageName)
                            .field("latest.old", currentLatest.get())
                            .field("latest.new", newLatest.get())
                            .log();
                    }
                }

                // Step 9: Rewrite metadata
                final byte[] resultBytes = ctx.rewriter.rewrite(filtered);

                // Log performance
                final long durationMs = (System.nanoTime() - ctx.startTime) / 1_000_000;
                EcsLogger.info("com.artipie.cooldown.metadata")
                    .message("Metadata filtering complete")
                    .eventCategory("cooldown")
                    .eventAction("metadata_filter")
                    .eventOutcome("success")
                    .field("repository.type", ctx.repoType)
                    .field("package.name", ctx.packageName)
                    .field("versions.total", ctx.allVersions.size())
                    .field("versions.blocked", blockedVersions.size())
                    .field("duration_ms", durationMs)
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
            }).whenComplete((result, error) -> {
                // Clear preloaded dates
                ctx.inspectorOpt.ifPresent(inspector -> {
                    if (inspector instanceof MetadataAwareInspector) {
                        ((MetadataAwareInspector) inspector).clearPreloadedDates();
                    }
                });
            });
    }

    /**
     * Evaluate cooldown for a single version.
     * Returns block status and blockedUntil timestamp for cache TTL calculation.
     */
    private CompletableFuture<VersionBlockResult> evaluateVersion(
        final String repoType,
        final String repoName,
        final String packageName,
        final String version,
        final Optional<CooldownInspector> inspectorOpt
    ) {
        // On cache miss, evaluate via cooldown service
        if (inspectorOpt.isEmpty()) {
            // No inspector - can't evaluate, allow by default
            return CompletableFuture.completedFuture(new VersionBlockResult(version, false, null));
        }
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
        return this.cooldown.evaluate(request, inspectorOpt.get())
            .thenApply(result -> {
                if (result.blocked()) {
                    // Extract blockedUntil from the block info
                    final Instant blockedUntil = result.block()
                        .map(block -> block.blockedUntil())
                        .orElse(null);
                    return new VersionBlockResult(version, true, blockedUntil);
                }
                return new VersionBlockResult(version, false, null);
            });
    }

    /**
     * Preload release dates from metadata into inspector if supported.
     */
    @SuppressWarnings("unchecked")
    private <T> void preloadReleaseDates(
        final MetadataParser<T> parser,
        final T parsed,
        final Optional<CooldownInspector> inspectorOpt
    ) {
        if (inspectorOpt.isEmpty()) {
            return;
        }
        final CooldownInspector inspector = inspectorOpt.get();
        if (!(inspector instanceof MetadataAwareInspector)) {
            return;
        }
        if (!(parser instanceof ReleaseDateProvider)) {
            return;
        }
        final ReleaseDateProvider<T> provider = (ReleaseDateProvider<T>) parser;
        final Map<String, Instant> releaseDates = provider.releaseDates(parsed);
        if (!releaseDates.isEmpty()) {
            ((MetadataAwareInspector) inspector).preloadReleaseDates(releaseDates);
            EcsLogger.debug("com.artipie.cooldown.metadata")
                .message("Preloaded release dates from metadata")
                .eventCategory("cooldown")
                .eventAction("metadata_filter")
                .field("dates.count", releaseDates.size())
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
        EcsLogger.debug("com.artipie.cooldown.metadata")
            .message("Invalidated metadata cache")
            .eventCategory("cooldown")
            .eventAction("cache_invalidate")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
            .field("package.name", packageName)
            .log();
    }

    @Override
    public void invalidateAll(final String repoType, final String repoName) {
        this.metadataCache.invalidateAll(repoType, repoName);
        if (CooldownMetrics.isAvailable()) {
            CooldownMetrics.getInstance().recordInvalidation(repoType, "unblock_all");
        }
        EcsLogger.debug("com.artipie.cooldown.metadata")
            .message("Invalidated all metadata cache for repository")
            .eventCategory("cooldown")
            .eventAction("cache_invalidate")
            .field("repository.type", repoType)
            .field("repository.name", repoName)
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
     * Check if version is a prerelease (alpha, beta, rc, canary, etc.).
     *
     * @param version Version string
     * @return true if prerelease
     */
    private static boolean isPrerelease(final String version) {
        final String v = version.toLowerCase(java.util.Locale.ROOT);
        return v.contains("-") || v.contains("alpha") || v.contains("beta")
            || v.contains("rc") || v.contains("canary") || v.contains("next")
            || v.contains("dev") || v.contains("snapshot");
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
        final Optional<CooldownInspector> inspectorOpt;
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
            final Optional<CooldownInspector> inspectorOpt,
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
            this.inspectorOpt = inspectorOpt;
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
