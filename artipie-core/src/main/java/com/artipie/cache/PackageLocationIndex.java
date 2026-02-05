/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import com.artipie.http.log.EcsLogger;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Two-tier cache tracking which group members have which packages.
 * L1: Caffeine in-memory cache (hot data, fast access).
 * L2: Valkey/Redis (optional, warm data, shared across instances).
 *
 * <p>Supports:
 * <ul>
 *   <li>TTL-based expiration for proxy/remote repos</li>
 *   <li>Event-driven invalidation for local repos (no TTL)</li>
 *   <li>Negative caching for NOT_EXISTS responses</li>
 * </ul>
 *
 * <p>Non-blocking design for Vert.x event loop compatibility.
 *
 * @since 1.18.0
 */
public final class PackageLocationIndex {

    /**
     * Logger name.
     */
    private static final String LOGGER = "com.artipie.cache";

    /**
     * L2 timeout in milliseconds.
     */
    private static final long L2_TIMEOUT_MS = 100L;

    /**
     * Group repository name.
     */
    private final String grpName;

    /**
     * Group settings for TTL configuration.
     */
    private final GroupSettings grpSettings;

    /**
     * L1 cache (in-memory, hot data).
     */
    private final Cache<String, PackageLocations> l1Cache;

    /**
     * L2 cache connection (optional).
     */
    private final Optional<ValkeyConnection> valkeyConn;

    /**
     * Create package location index.
     *
     * @param groupName Group repository name
     * @param settings Group settings for TTL configuration
     * @param valkey Optional Valkey connection for L2 cache
     */
    public PackageLocationIndex(
        final String groupName,
        final GroupSettings settings,
        final Optional<ValkeyConnection> valkey
    ) {
        this.grpName = Objects.requireNonNull(groupName);
        this.grpSettings = Objects.requireNonNull(settings);
        this.valkeyConn = Objects.requireNonNull(valkey);
        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(settings.cacheSizing().l1MaxEntries())
            .expireAfterWrite(computeL1Ttl(settings), TimeUnit.MILLISECONDS)
            .recordStats()
            .build();
        EcsLogger.debug(LOGGER)
            .message(String.format("PackageLocationIndex created (l1_max_entries=%d, l2_enabled=%b)", settings.cacheSizing().l1MaxEntries(), valkey.isPresent()))
            .eventCategory("cache")
            .eventAction("index_create")
            .field("repository.name", groupName)
            .log();
    }

    /**
     * Get locations for a package. Creates empty locations if not found.
     *
     * @param packageName Package name
     * @return Future with package locations
     */
    public CompletableFuture<PackageLocations> getLocations(
        final String packageName
    ) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        // Check L1 first
        final PackageLocations l1Result =
            this.l1Cache.getIfPresent(packageName);
        if (l1Result != null) {
            return CompletableFuture.completedFuture(l1Result);
        }
        // Check L2 if available
        if (this.valkeyConn.isPresent()) {
            return this.getFromL2(packageName)
                .thenApply(l2Result -> {
                    if (l2Result.isPresent()) {
                        final PackageLocations locations = l2Result.get();
                        this.l1Cache.put(packageName, locations);
                        return locations;
                    }
                    // Create new empty locations
                    final PackageLocations empty = new PackageLocations();
                    this.l1Cache.put(packageName, empty);
                    return empty;
                });
        }
        // No L2, create empty locations
        final PackageLocations empty = new PackageLocations();
        this.l1Cache.put(packageName, empty);
        return CompletableFuture.completedFuture(empty);
    }

    /**
     * Mark that a member has a package (EXISTS status).
     * Uses TTL from settings for proxy/remote repos.
     *
     * @param memberName Member repository name
     * @param packageName Package name
     * @return Future completing when marked
     */
    public CompletableFuture<Void> markExists(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        final Duration ttl = this.grpSettings.indexSettings().remoteExistsTtl();
        final Instant expiresAt = Instant.now().plus(ttl);
        return this.updateStatus(
            memberName,
            packageName,
            PackageLocations.LocationStatus.EXISTS,
            expiresAt
        );
    }

    /**
     * Mark that a member does not have a package (NOT_EXISTS status).
     * Uses negative cache TTL from settings.
     *
     * @param memberName Member repository name
     * @param packageName Package name
     * @return Future completing when marked
     */
    public CompletableFuture<Void> markNotExists(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        final Duration ttl =
            this.grpSettings.indexSettings().remoteNotExistsTtl();
        final Instant expiresAt = Instant.now().plus(ttl);
        return this.updateStatus(
            memberName,
            packageName,
            PackageLocations.LocationStatus.NOT_EXISTS,
            expiresAt
        );
    }

    /**
     * Mark that a local member has a package (EXISTS status, no TTL).
     * Uses Instant.MAX for event-driven invalidation (no automatic expiry).
     *
     * @param memberName Member repository name
     * @param packageName Package name
     * @return Future completing when marked
     */
    public CompletableFuture<Void> markExistsNoTtl(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        return this.updateStatus(
            memberName,
            packageName,
            PackageLocations.LocationStatus.EXISTS,
            Instant.MAX
        );
    }

    /**
     * Invalidate entire package entry (remove from both L1 and L2).
     *
     * @param packageName Package name to invalidate
     * @return Future completing when invalidated
     */
    public CompletableFuture<Void> invalidate(final String packageName) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        // Invalidate L1
        this.l1Cache.invalidate(packageName);
        // Invalidate L2 if available
        if (this.valkeyConn.isPresent()) {
            final String key = this.l2Key(packageName);
            return this.valkeyConn.get().async().del(key)
                .toCompletableFuture()
                .orTimeout(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .thenApply(v -> (Void) null)
                .exceptionally(err -> {
                    EcsLogger.warn(LOGGER)
                        .message("L2 invalidate failed")
                        .eventCategory("cache")
                        .eventAction("l2_invalidate")
                        .eventOutcome("failure")
                        .field("repository.name", this.grpName)
                        .field("package.name", packageName)
                        .error(err)
                        .log();
                    return null;
                });
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate specific member from a package entry.
     *
     * @param memberName Member to invalidate
     * @param packageName Package name
     * @return Future completing when invalidated
     */
    public CompletableFuture<Void> invalidateMember(
        final String memberName,
        final String packageName
    ) {
        Objects.requireNonNull(memberName, "memberName cannot be null");
        Objects.requireNonNull(packageName, "packageName cannot be null");
        return this.getLocations(packageName)
            .thenCompose(locations -> {
                locations.remove(memberName);
                // Update L2 if available
                if (this.valkeyConn.isPresent()) {
                    return this.saveToL2(packageName, locations);
                }
                return CompletableFuture.completedFuture(null);
            });
    }

    /**
     * Update status for a member/package combination.
     *
     * @param memberName Member name
     * @param packageName Package name
     * @param status New status
     * @param expiresAt Expiration time
     * @return Future completing when updated
     */
    private CompletableFuture<Void> updateStatus(
        final String memberName,
        final String packageName,
        final PackageLocations.LocationStatus status,
        final Instant expiresAt
    ) {
        return this.getLocations(packageName)
            .thenCompose(locations -> {
                locations.setStatus(memberName, status, expiresAt);
                // Update L2 if available
                if (this.valkeyConn.isPresent()) {
                    return this.saveToL2(packageName, locations);
                }
                return CompletableFuture.completedFuture(null);
            });
    }

    /**
     * Get from L2 cache.
     *
     * @param packageName Package name
     * @return Future with optional locations
     */
    private CompletableFuture<Optional<PackageLocations>> getFromL2(
        final String packageName
    ) {
        final String key = this.l2Key(packageName);
        return this.valkeyConn.get().async().get(key)
            .toCompletableFuture()
            .orTimeout(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .<Optional<PackageLocations>>thenApply(bytes -> {
                if (bytes == null) {
                    return Optional.empty();
                }
                return this.deserialize(bytes);
            })
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("L2 get failed")
                    .eventCategory("cache")
                    .eventAction("l2_get")
                    .eventOutcome("failure")
                    .field("repository.name", this.grpName)
                    .field("package.name", packageName)
                    .error(err)
                    .log();
                return Optional.empty();
            });
    }

    /**
     * Save to L2 cache.
     *
     * @param packageName Package name
     * @param locations Locations to save
     * @return Future completing when saved
     */
    private CompletableFuture<Void> saveToL2(
        final String packageName,
        final PackageLocations locations
    ) {
        final String key = this.l2Key(packageName);
        final byte[] value = this.serialize(locations);
        // Use metadata TTL for L2 entries
        final long seconds =
            this.grpSettings.metadataSettings().ttl().getSeconds();
        return this.valkeyConn.get().async().setex(key, seconds, value)
            .toCompletableFuture()
            .orTimeout(L2_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .thenApply(v -> (Void) null)
            .exceptionally(err -> {
                EcsLogger.warn(LOGGER)
                    .message("L2 save failed")
                    .eventCategory("cache")
                    .eventAction("l2_save")
                    .eventOutcome("failure")
                    .field("repository.name", this.grpName)
                    .field("package.name", packageName)
                    .error(err)
                    .log();
                return null;
            });
    }

    /**
     * Compute L2 cache key.
     *
     * @param packageName Package name
     * @return Redis key
     */
    private String l2Key(final String packageName) {
        return String.format("pli:%s:%s", this.grpName, packageName);
    }

    /**
     * Serialize locations to JSON bytes.
     * Simple JSON format without Jackson dependency.
     *
     * @param locations Locations to serialize
     * @return JSON bytes
     */
    private byte[] serialize(final PackageLocations locations) {
        final StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (final Map.Entry<String, PackageLocations.LocationEntry> entry
            : locations.entries().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            // Handle Instant.MAX specially - toEpochMilli() throws ArithmeticException
            final long epochMilli = entry.getValue().expiresAt().equals(Instant.MAX)
                ? Long.MAX_VALUE
                : entry.getValue().expiresAt().toEpochMilli();
            json.append('"')
                .append(escapeJson(entry.getKey()))
                .append("\":{\"s\":\"")
                .append(entry.getValue().status().name())
                .append("\",\"e\":")
                .append(epochMilli)
                .append('}');
        }
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserialize locations from JSON bytes.
     *
     * @param bytes JSON bytes
     * @return Optional locations
     */
    private Optional<PackageLocations> deserialize(final byte[] bytes) {
        try {
            final String json = new String(bytes, StandardCharsets.UTF_8);
            final PackageLocations locations = new PackageLocations();
            // Simple JSON parsing without Jackson
            // Format: {"member":{"s":"EXISTS","e":1234567890},...}
            if (json.length() <= 2) {
                return Optional.of(locations);
            }
            // Remove outer braces
            final String content = json.substring(1, json.length() - 1);
            int pos = 0;
            while (pos < content.length()) {
                // Find member name
                final int keyStart = content.indexOf('"', pos);
                if (keyStart < 0) {
                    break;
                }
                final int keyEnd = content.indexOf('"', keyStart + 1);
                final String substr = content.substring(keyStart + 1, keyEnd);
                final String member = unescapeJson(substr);
                // Find status value
                final int statStart = content.indexOf("\"s\":\"", keyEnd) + 5;
                final int statEnd = content.indexOf('"', statStart);
                final String statStr = content.substring(statStart, statEnd);
                final PackageLocations.LocationStatus status =
                    PackageLocations.LocationStatus.valueOf(statStr);
                // Find expires value
                final int expStart = content.indexOf("\"e\":", statEnd) + 4;
                int expEnd = content.indexOf('}', expStart);
                final long epochMilli = Long.parseLong(
                    content.substring(expStart, expEnd).trim()
                );
                // Handle Long.MAX_VALUE specially - reconstruct Instant.MAX
                final Instant expAt = epochMilli == Long.MAX_VALUE
                    ? Instant.MAX
                    : Instant.ofEpochMilli(epochMilli);
                locations.setStatus(member, status, expAt);
                // Move to next entry
                pos = expEnd + 1;
                // Skip comma if present
                if (pos < content.length() && content.charAt(pos) == ',') {
                    pos++;
                }
            }
            return Optional.of(locations);
        } catch (final Exception ex) {
            EcsLogger.warn(LOGGER)
                .message("Failed to deserialize locations")
                .eventCategory("cache")
                .eventAction("deserialize")
                .eventOutcome("failure")
                .field("repository.name", this.grpName)
                .error(ex)
                .log();
            return Optional.empty();
        }
    }

    /**
     * Escape JSON string.
     *
     * @param str String to escape
     * @return Escaped string
     */
    private static String escapeJson(final String str) {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * Unescape JSON string.
     *
     * @param str String to unescape
     * @return Unescaped string
     */
    private static String unescapeJson(final String str) {
        // Handle escaped backslashes first to correctly handle sequences like \\"
        return str.replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    /**
     * Compute L1 TTL based on settings.
     * Uses shorter of remote exists TTL for faster invalidation in L1.
     *
     * @param settings Group settings
     * @return L1 TTL in milliseconds
     */
    private static long computeL1Ttl(final GroupSettings settings) {
        // L1 should have shorter TTL than L2 for consistency
        // Use 2x the remote exists TTL or max 1 hour
        final Duration remoteTtl = settings.indexSettings().remoteExistsTtl();
        final Duration maxTtl = Duration.ofHours(1);
        final Duration l1Ttl = remoteTtl.multipliedBy(2);
        if (l1Ttl.compareTo(maxTtl) < 0) {
            return l1Ttl.toMillis();
        }
        return maxTtl.toMillis();
    }

    @Override
    public String toString() {
        return String.format(
            "PackageLocationIndex{group=%s, l1Size=%d, l2=%s}",
            this.grpName,
            this.l1Cache.estimatedSize(),
            this.valkeyConn.isPresent() ? "enabled" : "disabled"
        );
    }
}
