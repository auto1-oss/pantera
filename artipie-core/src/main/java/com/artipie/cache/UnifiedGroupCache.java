/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.http.log.EcsLogger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Unified cache orchestrator for group repositories.
 * Coordinates PackageLocationIndex, MergedMetadataCache, and member fetching.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Checks MergedMetadataCache for cached merged results</li>
 *   <li>On cache miss, fetches from members in parallel</li>
 *   <li>Merges results using adapter-specific MetadataMerger</li>
 *   <li>Updates PackageLocationIndex based on fetch results</li>
 *   <li>Handles local publish/delete events for immediate index updates</li>
 * </ul>
 *
 * <p>Non-blocking design for Vert.x event loop compatibility.
 *
 * @since 1.18.0
 */
public final class UnifiedGroupCache implements AutoCloseable {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.artipie.cache";

    /**
     * Group repository name.
     */
    private final String grpName;

    /**
     * Package location index.
     */
    private final PackageLocationIndex locationIndex;

    /**
     * Merged metadata cache.
     */
    private final MergedMetadataCache metadataCache;

    /**
     * Create unified group cache.
     *
     * @param groupName Group repository name
     * @param settings Group settings for TTL configuration
     * @param valkey Optional Valkey connection for L2 cache
     */
    public UnifiedGroupCache(
        final String groupName,
        final GroupSettings settings,
        final Optional<ValkeyConnection> valkey
    ) {
        this.grpName = Objects.requireNonNull(groupName, "groupName cannot be null");
        Objects.requireNonNull(settings, "settings cannot be null");
        Objects.requireNonNull(valkey, "valkey cannot be null");
        this.locationIndex = new PackageLocationIndex(groupName, settings, valkey);
        this.metadataCache = new MergedMetadataCache(groupName, settings, valkey);
        EcsLogger.debug(LOGGER)
            .message("UnifiedGroupCache created")
            .eventCategory("cache")
            .eventAction("group_cache_create")
            .field("repository.name", groupName)
            .log();
    }

    /**
     * Get merged metadata for a package.
     * First checks cache, then fetches from members in parallel if cache miss.
     *
     * @param adapterType Adapter type (npm, maven, pypi, etc.)
     * @param packageName Package name
     * @param memberFetchers List of member fetchers
     * @param merger Metadata merger for this adapter type
     * @return Future with optional merged metadata bytes
     */
    public CompletableFuture<Optional<byte[]>> getMetadata(
        final String adapterType,
        final String packageName,
        final List<MemberFetcher> memberFetchers,
        final MetadataMerger merger
    ) {
        Objects.requireNonNull(adapterType, "adapterType cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        Objects.requireNonNull(memberFetchers, "memberFetchers cannot be null");
        Objects.requireNonNull(merger, "merger cannot be null");
        // Check cache first
        return this.metadataCache.get(adapterType, packageName)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    EcsLogger.trace(LOGGER)
                        .message("Metadata cache hit")
                        .eventCategory("cache")
                        .eventAction("metadata_hit")
                        .field("repository.name", this.grpName)
                        .field("repository.type", adapterType)
                        .field("package.name", packageName)
                        .log();
                    return CompletableFuture.completedFuture(cached);
                }
                // Cache miss - fetch from members in parallel
                EcsLogger.trace(LOGGER)
                    .message(String.format("Metadata cache miss, fetching from %d members", memberFetchers.size()))
                    .eventCategory("cache")
                    .eventAction("metadata_miss")
                    .field("repository.name", this.grpName)
                    .field("repository.type", adapterType)
                    .field("package.name", packageName)
                    .log();
                return this.fetchAndMerge(adapterType, packageName, memberFetchers, merger);
            });
    }

    /**
     * Get package locations from index.
     *
     * @param packageName Package name
     * @return Future with package locations
     */
    public CompletableFuture<PackageLocations> getLocations(final String packageName) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        return this.locationIndex.getLocations(packageName);
    }

    /**
     * Pre-populate metadata cache.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @param metadata Metadata bytes to cache
     * @return Future completing when cached
     */
    public CompletableFuture<Void> cacheMetadata(
        final String adapterType,
        final String packageName,
        final byte[] metadata
    ) {
        Objects.requireNonNull(adapterType, "adapterType cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");
        return this.metadataCache.put(adapterType, packageName, metadata);
    }

    /**
     * Handle local repository publish event.
     * Marks member as EXISTS (no TTL) and invalidates metadata cache.
     *
     * @param memberName Local member repository name
     * @param packageName Package name that was published
     * @return Future completing when processed
     */
    public CompletableFuture<Void> onLocalPublish(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        EcsLogger.debug(LOGGER)
            .message("Local publish event")
            .eventCategory("cache")
            .eventAction("local_publish")
            .field("repository.name", this.grpName)
            .field("member.name", memberName)
            .field("package.name", packageName)
            .log();
        // Mark EXISTS with no TTL (event-driven invalidation)
        final CompletableFuture<Void> markFuture =
            this.locationIndex.markExistsNoTtl(memberName, packageName);
        // Invalidate metadata cache
        final CompletableFuture<Void> invalidateFuture =
            this.metadataCache.invalidate(packageName);
        return CompletableFuture.allOf(markFuture, invalidateFuture);
    }

    /**
     * Handle local repository delete event.
     * Removes member from index and invalidates metadata cache.
     *
     * @param memberName Local member repository name
     * @param packageName Package name that was deleted
     * @return Future completing when processed
     */
    public CompletableFuture<Void> onLocalDelete(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        EcsLogger.debug(LOGGER)
            .message("Local delete event")
            .eventCategory("cache")
            .eventAction("local_delete")
            .field("repository.name", this.grpName)
            .field("member.name", memberName)
            .field("package.name", packageName)
            .log();
        // Remove member from index
        final CompletableFuture<Void> invalidateMemberFuture =
            this.locationIndex.invalidateMember(memberName, packageName);
        // Invalidate metadata cache
        final CompletableFuture<Void> invalidateMetaFuture =
            this.metadataCache.invalidate(packageName);
        return CompletableFuture.allOf(invalidateMemberFuture, invalidateMetaFuture);
    }

    /**
     * Record successful fetch from a member (hit).
     * Marks member as EXISTS with TTL.
     *
     * @param memberName Member repository name
     * @param packageName Package name
     * @return Future completing when recorded
     */
    public CompletableFuture<Void> recordMemberHit(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        return this.locationIndex.markExists(memberName, packageName);
    }

    /**
     * Record 404 from a member (miss/negative cache).
     * Marks member as NOT_EXISTS with negative cache TTL.
     *
     * @param memberName Member repository name
     * @param packageName Package name
     * @return Future completing when recorded
     */
    public CompletableFuture<Void> recordMemberMiss(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        return this.locationIndex.markNotExists(memberName, packageName);
    }

    @Override
    public void close() {
        EcsLogger.debug(LOGGER)
            .message("UnifiedGroupCache closed")
            .eventCategory("cache")
            .eventAction("group_cache_close")
            .field("repository.name", this.grpName)
            .log();
    }

    @Override
    public String toString() {
        return String.format(
            "UnifiedGroupCache{group=%s, locationIndex=%s, metadataCache=%s}",
            this.grpName,
            this.locationIndex,
            this.metadataCache
        );
    }

    /**
     * Fetch from members in parallel, merge results, and update index.
     *
     * @param adapterType Adapter type
     * @param packageName Package name
     * @param fetchers List of member fetchers
     * @param merger Metadata merger
     * @return Future with optional merged metadata
     */
    private CompletableFuture<Optional<byte[]>> fetchAndMerge(
        final String adapterType,
        final String packageName,
        final List<MemberFetcher> fetchers,
        final MetadataMerger merger
    ) {
        // Fetch from all members in parallel
        final List<CompletableFuture<FetchResult>> fetchFutures = fetchers.stream()
            .map(fetcher -> this.fetchFromMember(fetcher, packageName))
            .collect(Collectors.toList());
        // Wait for all fetches to complete
        return CompletableFuture.allOf(
            fetchFutures.toArray(new CompletableFuture[0])
        ).thenCompose(v -> {
            // Collect results maintaining order
            final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
            for (final CompletableFuture<FetchResult> future : fetchFutures) {
                final FetchResult result = future.join();
                if (result.data().isPresent()) {
                    responses.put(result.memberName(), result.data().get());
                }
            }
            // If no members have data, return empty
            if (responses.isEmpty()) {
                EcsLogger.trace(LOGGER)
                    .message("No members have package")
                    .eventCategory("cache")
                    .eventAction("fetch_empty")
                    .field("repository.name", this.grpName)
                    .field("repository.type", adapterType)
                    .field("package.name", packageName)
                    .log();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            // Merge responses
            final byte[] merged = merger.merge(responses);
            EcsLogger.trace(LOGGER)
                .message(String.format("Merged metadata from %d members (merged_size=%d)", responses.size(), merged.length))
                .eventCategory("cache")
                .eventAction("merge_complete")
                .field("repository.name", this.grpName)
                .field("repository.type", adapterType)
                .field("package.name", packageName)
                .log();
            // Cache merged result
            return this.metadataCache.put(adapterType, packageName, merged)
                .thenApply(ignored -> Optional.of(merged));
        });
    }

    /**
     * Fetch from a single member and update index.
     *
     * @param fetcher Member fetcher
     * @param packageName Package name
     * @return Future with fetch result
     */
    private CompletableFuture<FetchResult> fetchFromMember(
        final MemberFetcher fetcher,
        final String packageName
    ) {
        final String memberName = fetcher.memberName();
        return fetcher.fetch()
            .thenCompose(data -> {
                // Update index based on result
                final CompletableFuture<Void> indexUpdate;
                if (data.isPresent()) {
                    indexUpdate = this.locationIndex.markExists(memberName, packageName);
                } else {
                    indexUpdate = this.locationIndex.markNotExists(memberName, packageName);
                }
                return indexUpdate.thenApply(v -> new FetchResult(memberName, data));
            })
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("Fetch from member failed")
                    .eventCategory("cache")
                    .eventAction("member_fetch")
                    .eventOutcome("failure")
                    .field("repository.name", this.grpName)
                    .field("member.name", memberName)
                    .field("package.name", packageName)
                    .error(err)
                    .log();
                return new FetchResult(memberName, Optional.empty());
            });
    }

    /**
     * Interface for fetching metadata from a group member.
     */
    public interface MemberFetcher {

        /**
         * Get the member repository name.
         *
         * @return Member name
         */
        String memberName();

        /**
         * Fetch metadata from this member.
         *
         * @return Future with optional metadata bytes
         */
        CompletableFuture<Optional<byte[]>> fetch();
    }

    /**
     * Result of fetching from a member.
     *
     * @param memberName Member name
     * @param data Optional metadata bytes
     */
    private record FetchResult(String memberName, Optional<byte[]> data) { }
}
