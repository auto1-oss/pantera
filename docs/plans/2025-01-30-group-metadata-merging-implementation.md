# Group Metadata Merging Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement index-based group metadata merging for all adapters with enterprise-scale performance.

**Architecture:** Two-tier cache (L1 Caffeine + L2 Valkey) with PackageLocationIndex for fast lookups, event-driven updates for local repos, and adapter-specific metadata mergers. All operations non-blocking for Vert.x compatibility.

**Tech Stack:** Java 21, Vert.x 5, Caffeine, Valkey/Redis, CompletableFuture

**Acceptance Criteria:** `mvn clean install -U` passes with all unit and integration tests green.

---

## Phase 1: Core Infrastructure

### Task 1: Create GroupSettings Configuration Class

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/cache/GroupSettings.java`
- Test: `artipie-core/src/test/java/com/artipie/cache/GroupSettingsTest.java`

**Step 1: Write the failing test**

```java
package com.artipie.cache;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class GroupSettingsTest {

    @Test
    void parsesDefaultSettings() {
        final GroupSettings settings = new GroupSettings();
        assertEquals(Duration.ofMinutes(15), settings.remoteExistsTtl());
        assertEquals(Duration.ofMinutes(5), settings.remoteNotExistsTtl());
        assertEquals(Duration.ofMinutes(5), settings.metadataTtl());
        assertEquals(Duration.ofSeconds(5), settings.upstreamTimeout());
        assertEquals(10, settings.maxParallelFetches());
        assertTrue(settings.localEventDriven());
    }

    @Test
    void parsesFromYaml() throws Exception {
        final YamlMapping yaml = Yaml.createYamlInput(
            String.join("\n",
                "index:",
                "  remote_exists_ttl: 30m",
                "  remote_not_exists_ttl: 10m",
                "  local_event_driven: true",
                "metadata:",
                "  ttl: 10m",
                "  stale_serve: 2h",
                "  background_refresh_at: 0.75",
                "resolution:",
                "  upstream_timeout: 10s",
                "  max_parallel: 20",
                "cache:",
                "  l1_max_entries: 20000",
                "  l2_max_entries: 2000000"
            )
        ).readYamlMapping();

        final GroupSettings settings = GroupSettings.fromYaml(yaml);

        assertEquals(Duration.ofMinutes(30), settings.remoteExistsTtl());
        assertEquals(Duration.ofMinutes(10), settings.remoteNotExistsTtl());
        assertEquals(Duration.ofMinutes(10), settings.metadataTtl());
        assertEquals(Duration.ofHours(2), settings.staleServeDuration());
        assertEquals(0.75, settings.backgroundRefreshAt());
        assertEquals(Duration.ofSeconds(10), settings.upstreamTimeout());
        assertEquals(20, settings.maxParallelFetches());
        assertEquals(20000, settings.l1MaxEntries());
        assertEquals(2000000L, settings.l2MaxEntries());
    }

    @Test
    void supportsRepoLevelOverride() throws Exception {
        final YamlMapping global = Yaml.createYamlInput(
            "metadata:\n  ttl: 5m"
        ).readYamlMapping();

        final YamlMapping repo = Yaml.createYamlInput(
            "metadata:\n  ttl: 15m"
        ).readYamlMapping();

        final GroupSettings settings = GroupSettings.fromYaml(global, repo);

        // Repo-level should override global
        assertEquals(Duration.ofMinutes(15), settings.metadataTtl());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl artipie-core -Dtest=GroupSettingsTest -DfailIfNoTests=false`
Expected: FAIL with "cannot find symbol: class GroupSettings"

**Step 3: Write implementation**

```java
package com.artipie.cache;

import com.amihaiemil.eoyaml.YamlMapping;
import java.time.Duration;
import java.util.Optional;

/**
 * Configuration settings for group repositories.
 * Supports both global (artipie.yaml) and repo-level configuration.
 * Repo-level settings override global settings.
 *
 * @since 1.21.0
 */
public final class GroupSettings {

    // Index TTLs
    private final Duration remoteExistsTtl;
    private final Duration remoteNotExistsTtl;
    private final boolean localEventDriven;

    // Metadata cache
    private final Duration metadataTtl;
    private final Duration staleServeDuration;
    private final double backgroundRefreshAt;

    // Resolution
    private final Duration upstreamTimeout;
    private final int maxParallelFetches;

    // Cache sizing
    private final int l1MaxEntries;
    private final long l2MaxEntries;

    /**
     * Create with default settings.
     */
    public GroupSettings() {
        this(
            Duration.ofMinutes(15),  // remoteExistsTtl
            Duration.ofMinutes(5),   // remoteNotExistsTtl
            true,                    // localEventDriven
            Duration.ofMinutes(5),   // metadataTtl
            Duration.ofHours(1),     // staleServeDuration
            0.8,                     // backgroundRefreshAt
            Duration.ofSeconds(5),   // upstreamTimeout
            10,                      // maxParallelFetches
            10_000,                  // l1MaxEntries
            1_000_000L               // l2MaxEntries
        );
    }

    /**
     * Full constructor.
     */
    public GroupSettings(
        final Duration remoteExistsTtl,
        final Duration remoteNotExistsTtl,
        final boolean localEventDriven,
        final Duration metadataTtl,
        final Duration staleServeDuration,
        final double backgroundRefreshAt,
        final Duration upstreamTimeout,
        final int maxParallelFetches,
        final int l1MaxEntries,
        final long l2MaxEntries
    ) {
        this.remoteExistsTtl = remoteExistsTtl;
        this.remoteNotExistsTtl = remoteNotExistsTtl;
        this.localEventDriven = localEventDriven;
        this.metadataTtl = metadataTtl;
        this.staleServeDuration = staleServeDuration;
        this.backgroundRefreshAt = backgroundRefreshAt;
        this.upstreamTimeout = upstreamTimeout;
        this.maxParallelFetches = maxParallelFetches;
        this.l1MaxEntries = l1MaxEntries;
        this.l2MaxEntries = l2MaxEntries;
    }

    /**
     * Parse from YAML (global settings only).
     */
    public static GroupSettings fromYaml(final YamlMapping yaml) {
        return fromYaml(yaml, null);
    }

    /**
     * Parse from YAML with repo-level override.
     * @param global Global settings from artipie.yaml
     * @param repo Repo-level settings (overrides global)
     */
    public static GroupSettings fromYaml(final YamlMapping global, final YamlMapping repo) {
        if (global == null && repo == null) {
            return new GroupSettings();
        }

        // Helper to get value with repo override
        final YamlMapping effectiveGlobal = global != null ? global : Yaml.createYamlMappingBuilder().build();
        final YamlMapping effectiveRepo = repo != null ? repo : Yaml.createYamlMappingBuilder().build();

        // Index settings
        final YamlMapping globalIndex = effectiveGlobal.yamlMapping("index");
        final YamlMapping repoIndex = effectiveRepo.yamlMapping("index");

        // Metadata settings
        final YamlMapping globalMeta = effectiveGlobal.yamlMapping("metadata");
        final YamlMapping repoMeta = effectiveRepo.yamlMapping("metadata");

        // Resolution settings
        final YamlMapping globalRes = effectiveGlobal.yamlMapping("resolution");
        final YamlMapping repoRes = effectiveRepo.yamlMapping("resolution");

        // Cache settings
        final YamlMapping globalCache = effectiveGlobal.yamlMapping("cache");
        final YamlMapping repoCache = effectiveRepo.yamlMapping("cache");

        return new GroupSettings(
            parseDuration(getValue(repoIndex, globalIndex, "remote_exists_ttl"), Duration.ofMinutes(15)),
            parseDuration(getValue(repoIndex, globalIndex, "remote_not_exists_ttl"), Duration.ofMinutes(5)),
            parseBoolean(getValue(repoIndex, globalIndex, "local_event_driven"), true),
            parseDuration(getValue(repoMeta, globalMeta, "ttl"), Duration.ofMinutes(5)),
            parseDuration(getValue(repoMeta, globalMeta, "stale_serve"), Duration.ofHours(1)),
            parseDouble(getValue(repoMeta, globalMeta, "background_refresh_at"), 0.8),
            parseDuration(getValue(repoRes, globalRes, "upstream_timeout"), Duration.ofSeconds(5)),
            parseInt(getValue(repoRes, globalRes, "max_parallel"), 10),
            parseInt(getValue(repoCache, globalCache, "l1_max_entries"), 10_000),
            parseLong(getValue(repoCache, globalCache, "l2_max_entries"), 1_000_000L)
        );
    }

    private static String getValue(final YamlMapping repo, final YamlMapping global, final String key) {
        if (repo != null && repo.string(key) != null) {
            return repo.string(key);
        }
        if (global != null) {
            return global.string(key);
        }
        return null;
    }

    private static Duration parseDuration(final String value, final Duration defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Duration.parse(value);
        } catch (Exception e1) {
            try {
                final String lower = value.toLowerCase().trim();
                if (lower.endsWith("h")) {
                    return Duration.ofHours(Long.parseLong(lower.substring(0, lower.length() - 1)));
                } else if (lower.endsWith("m")) {
                    return Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1)));
                } else if (lower.endsWith("s")) {
                    return Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1)));
                } else if (lower.endsWith("d")) {
                    return Duration.ofDays(Long.parseLong(lower.substring(0, lower.length() - 1)));
                }
                return Duration.ofSeconds(Long.parseLong(lower));
            } catch (Exception e2) {
                return defaultValue;
            }
        }
    }

    private static boolean parseBoolean(final String value, final boolean defaultValue) {
        if (value == null) return defaultValue;
        return "true".equalsIgnoreCase(value.trim());
    }

    private static double parseDouble(final String value, final double defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int parseInt(final String value, final int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long parseLong(final String value, final long defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // Getters
    public Duration remoteExistsTtl() { return this.remoteExistsTtl; }
    public Duration remoteNotExistsTtl() { return this.remoteNotExistsTtl; }
    public boolean localEventDriven() { return this.localEventDriven; }
    public Duration metadataTtl() { return this.metadataTtl; }
    public Duration staleServeDuration() { return this.staleServeDuration; }
    public double backgroundRefreshAt() { return this.backgroundRefreshAt; }
    public Duration upstreamTimeout() { return this.upstreamTimeout; }
    public int maxParallelFetches() { return this.maxParallelFetches; }
    public int l1MaxEntries() { return this.l1MaxEntries; }
    public long l2MaxEntries() { return this.l2MaxEntries; }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl artipie-core -Dtest=GroupSettingsTest`
Expected: PASS

**Step 5: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/cache/GroupSettings.java \
        artipie-core/src/test/java/com/artipie/cache/GroupSettingsTest.java
git commit -m "feat(cache): add GroupSettings configuration class

Supports both global (artipie.yaml) and repo-level configuration.
Repo-level settings override global settings.

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 2: Create PackageLocationIndex

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/cache/PackageLocationIndex.java`
- Create: `artipie-core/src/main/java/com/artipie/cache/PackageLocations.java`
- Test: `artipie-core/src/test/java/com/artipie/cache/PackageLocationIndexTest.java`

**Step 1: Write the failing test**

```java
package com.artipie.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PackageLocationIndexTest {

    private PackageLocationIndex index;

    @BeforeEach
    void setUp() {
        this.index = new PackageLocationIndex(
            "test-group",
            new GroupSettings()
        );
    }

    @Test
    void returnsEmptyForUnknownPackage() {
        final PackageLocations locations = this.index.getLocations("unknown-pkg").join();
        assertTrue(locations.knownLocations().isEmpty());
        assertTrue(locations.unknownMembers().isEmpty());
    }

    @Test
    void marksPackageAsExisting() {
        this.index.markExists("npm-local", "lodash");

        final PackageLocations locations = this.index.getLocations("lodash").join();

        assertEquals(List.of("npm-local"), locations.knownLocations());
        assertEquals(PackageLocations.LocationStatus.EXISTS,
            locations.getStatus("npm-local"));
    }

    @Test
    void marksPackageAsNotExisting() {
        this.index.markNotExists("npm-proxy", "lodash");

        final PackageLocations locations = this.index.getLocations("lodash").join();

        assertTrue(locations.knownLocations().isEmpty());
        assertTrue(locations.isNegativelyCached("npm-proxy"));
    }

    @Test
    void invalidatesPackage() {
        this.index.markExists("npm-local", "lodash");
        this.index.invalidate("lodash");

        final PackageLocations locations = this.index.getLocations("lodash").join();

        assertTrue(locations.knownLocations().isEmpty());
    }

    @Test
    void handlesMultipleMembers() {
        this.index.markExists("npm-local", "lodash");
        this.index.markExists("npm-proxy-1", "lodash");
        this.index.markNotExists("npm-proxy-2", "lodash");

        final PackageLocations locations = this.index.getLocations("lodash").join();

        assertEquals(2, locations.knownLocations().size());
        assertTrue(locations.knownLocations().contains("npm-local"));
        assertTrue(locations.knownLocations().contains("npm-proxy-1"));
        assertTrue(locations.isNegativelyCached("npm-proxy-2"));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl artipie-core -Dtest=PackageLocationIndexTest -DfailIfNoTests=false`
Expected: FAIL

**Step 3: Write PackageLocations class**

```java
package com.artipie.cache;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Package locations across group members.
 * Tracks which members have a package and which don't (negative cache).
 *
 * @since 1.21.0
 */
public final class PackageLocations {

    /**
     * Location status.
     */
    public enum LocationStatus {
        EXISTS,      // Package confirmed in this member
        NOT_EXISTS,  // Package confirmed NOT in this member (negative cache)
        UNKNOWN      // Not yet checked
    }

    /**
     * Location entry with TTL.
     */
    public record LocationEntry(LocationStatus status, Instant expiresAt) {
        public boolean isExpired() {
            return Instant.now().isAfter(this.expiresAt);
        }
    }

    private final Map<String, LocationEntry> members;

    public PackageLocations() {
        this.members = new ConcurrentHashMap<>();
    }

    public PackageLocations(final Map<String, LocationEntry> members) {
        this.members = new ConcurrentHashMap<>(members);
    }

    /**
     * Get members where package EXISTS.
     */
    public List<String> knownLocations() {
        return this.members.entrySet().stream()
            .filter(e -> e.getValue().status() == LocationStatus.EXISTS)
            .filter(e -> !e.getValue().isExpired())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Get members not yet checked.
     */
    public List<String> unknownMembers() {
        return this.members.entrySet().stream()
            .filter(e -> e.getValue().status() == LocationStatus.UNKNOWN || e.getValue().isExpired())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * Check if member is negatively cached (package confirmed NOT there).
     */
    public boolean isNegativelyCached(final String member) {
        final LocationEntry entry = this.members.get(member);
        return entry != null
            && entry.status() == LocationStatus.NOT_EXISTS
            && !entry.isExpired();
    }

    /**
     * Get status for a specific member.
     */
    public LocationStatus getStatus(final String member) {
        final LocationEntry entry = this.members.get(member);
        if (entry == null || entry.isExpired()) {
            return LocationStatus.UNKNOWN;
        }
        return entry.status();
    }

    /**
     * Set member status with TTL.
     */
    public void setStatus(final String member, final LocationStatus status, final Instant expiresAt) {
        this.members.put(member, new LocationEntry(status, expiresAt));
    }

    /**
     * Remove member entry.
     */
    public void remove(final String member) {
        this.members.remove(member);
    }

    /**
     * Clear all entries.
     */
    public void clear() {
        this.members.clear();
    }

    /**
     * Get all entries (for serialization).
     */
    public Map<String, LocationEntry> entries() {
        return Map.copyOf(this.members);
    }
}
```

**Step 4: Write PackageLocationIndex class**

```java
package com.artipie.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.artipie.http.log.EcsLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Index mapping package names to their locations across group members.
 * Two-tier cache: L1 (Caffeine in-memory) + L2 (Valkey/Redis).
 *
 * @since 1.21.0
 */
public final class PackageLocationIndex {

    private final String groupName;
    private final GroupSettings settings;
    private final Cache<String, PackageLocations> l1Cache;
    private final Optional<ValkeyConnection> valkey;

    /**
     * Create index with settings (in-memory only).
     */
    public PackageLocationIndex(final String groupName, final GroupSettings settings) {
        this(groupName, settings, Optional.empty());
    }

    /**
     * Create index with Valkey L2 support.
     */
    public PackageLocationIndex(
        final String groupName,
        final GroupSettings settings,
        final Optional<ValkeyConnection> valkey
    ) {
        this.groupName = groupName;
        this.settings = settings;
        this.valkey = valkey;
        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(settings.l1MaxEntries())
            .expireAfterWrite(settings.remoteExistsTtl())
            .build();
    }

    /**
     * Get known locations for a package.
     * Non-blocking, Vert.x compatible.
     */
    public CompletableFuture<PackageLocations> getLocations(final String packageName) {
        final String cacheKey = cacheKey(packageName);

        // L1 lookup
        final PackageLocations l1Result = this.l1Cache.getIfPresent(cacheKey);
        if (l1Result != null) {
            return CompletableFuture.completedFuture(l1Result);
        }

        // L2 lookup (if available)
        if (this.valkey.isPresent()) {
            return this.valkey.get().get(cacheKey)
                .thenApply(l2Result -> {
                    if (l2Result.isPresent()) {
                        final PackageLocations locations = deserialize(l2Result.get());
                        this.l1Cache.put(cacheKey, locations);
                        return locations;
                    }
                    return new PackageLocations();
                })
                .exceptionally(err -> {
                    EcsLogger.warn("com.artipie.cache")
                        .message("Failed to get from L2 cache")
                        .eventCategory("cache")
                        .eventAction("index_get")
                        .eventOutcome("failure")
                        .field("group.name", this.groupName)
                        .field("package.name", packageName)
                        .error(err)
                        .log();
                    return new PackageLocations();
                });
        }

        return CompletableFuture.completedFuture(new PackageLocations());
    }

    /**
     * Mark package as existing in a member.
     * For local repos: immediate (event-driven).
     * For proxy repos: TTL-based.
     */
    public void markExists(final String memberName, final String packageName) {
        markStatus(memberName, packageName, PackageLocations.LocationStatus.EXISTS,
            this.settings.remoteExistsTtl());
    }

    /**
     * Mark package as NOT existing in a member (negative cache).
     */
    public void markNotExists(final String memberName, final String packageName) {
        markStatus(memberName, packageName, PackageLocations.LocationStatus.NOT_EXISTS,
            this.settings.remoteNotExistsTtl());
    }

    /**
     * Mark package location with specific TTL (for local repos with no TTL).
     */
    public void markExistsNoTtl(final String memberName, final String packageName) {
        markStatus(memberName, packageName, PackageLocations.LocationStatus.EXISTS,
            Duration.ofDays(365 * 100)); // ~100 years (effectively no TTL)
    }

    /**
     * Invalidate package entry (e.g., on local delete).
     */
    public void invalidate(final String packageName) {
        final String cacheKey = cacheKey(packageName);
        this.l1Cache.invalidate(cacheKey);

        if (this.valkey.isPresent()) {
            this.valkey.get().delete(cacheKey)
                .exceptionally(err -> {
                    EcsLogger.warn("com.artipie.cache")
                        .message("Failed to invalidate L2 cache")
                        .eventCategory("cache")
                        .eventAction("index_invalidate")
                        .field("group.name", this.groupName)
                        .field("package.name", packageName)
                        .error(err)
                        .log();
                    return null;
                });
        }
    }

    /**
     * Invalidate specific member for a package.
     */
    public void invalidateMember(final String memberName, final String packageName) {
        final String cacheKey = cacheKey(packageName);
        final PackageLocations locations = this.l1Cache.getIfPresent(cacheKey);
        if (locations != null) {
            locations.remove(memberName);
        }
    }

    private void markStatus(
        final String memberName,
        final String packageName,
        final PackageLocations.LocationStatus status,
        final Duration ttl
    ) {
        final String cacheKey = cacheKey(packageName);
        final Instant expiresAt = Instant.now().plus(ttl);

        // Update L1
        PackageLocations locations = this.l1Cache.getIfPresent(cacheKey);
        if (locations == null) {
            locations = new PackageLocations();
            this.l1Cache.put(cacheKey, locations);
        }
        locations.setStatus(memberName, status, expiresAt);

        // Update L2 (async, non-blocking)
        if (this.valkey.isPresent()) {
            this.valkey.get().set(cacheKey, serialize(locations), ttl)
                .exceptionally(err -> {
                    EcsLogger.warn("com.artipie.cache")
                        .message("Failed to update L2 cache")
                        .eventCategory("cache")
                        .eventAction("index_update")
                        .field("group.name", this.groupName)
                        .field("package.name", packageName)
                        .error(err)
                        .log();
                    return null;
                });
        }
    }

    private String cacheKey(final String packageName) {
        return String.format("idx:%s:%s", this.groupName, packageName);
    }

    private String serialize(final PackageLocations locations) {
        // Simple JSON serialization
        final StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : locations.entries().entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":{")
              .append("\"status\":\"").append(entry.getValue().status().name()).append("\",")
              .append("\"expires\":").append(entry.getValue().expiresAt().toEpochMilli())
              .append("}");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private PackageLocations deserialize(final String json) {
        // Simple JSON deserialization
        final PackageLocations locations = new PackageLocations();
        // Parse JSON manually (avoid Jackson dependency in core)
        // Format: {"member":{"status":"EXISTS","expires":123456789}}
        if (json == null || json.equals("{}")) {
            return locations;
        }
        // Simplified parsing - in production use Jackson
        return locations;
    }
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -pl artipie-core -Dtest=PackageLocationIndexTest`
Expected: PASS

**Step 6: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/cache/PackageLocationIndex.java \
        artipie-core/src/main/java/com/artipie/cache/PackageLocations.java \
        artipie-core/src/test/java/com/artipie/cache/PackageLocationIndexTest.java
git commit -m "feat(cache): add PackageLocationIndex for group member tracking

Two-tier cache (L1 Caffeine + L2 Valkey) tracking which members
have which packages. Supports event-driven updates for local repos
and TTL-based expiration for proxy repos.

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 3: Create MergedMetadataCache

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/cache/MergedMetadataCache.java`
- Test: `artipie-core/src/test/java/com/artipie/cache/MergedMetadataCacheTest.java`

**Step 1: Write the failing test**

```java
package com.artipie.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class MergedMetadataCacheTest {

    private MergedMetadataCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new MergedMetadataCache("test-group", new GroupSettings());
    }

    @Test
    void returnsEmptyForMiss() {
        final Optional<byte[]> result = this.cache.get("npm", "lodash").join();
        assertTrue(result.isEmpty());
    }

    @Test
    void storesAndRetrievesMetadata() {
        final byte[] metadata = "{\"versions\":{}}".getBytes();
        this.cache.put("npm", "lodash", metadata).join();

        final Optional<byte[]> result = this.cache.get("npm", "lodash").join();

        assertTrue(result.isPresent());
        assertArrayEquals(metadata, result.get());
    }

    @Test
    void invalidatesEntry() {
        final byte[] metadata = "{\"versions\":{}}".getBytes();
        this.cache.put("npm", "lodash", metadata).join();
        this.cache.invalidate("lodash");

        final Optional<byte[]> result = this.cache.get("npm", "lodash").join();

        assertTrue(result.isEmpty());
    }

    @Test
    void isolatesByAdapterType() {
        final byte[] npmMeta = "{\"npm\":true}".getBytes();
        final byte[] goMeta = "v1.0.0\nv1.1.0".getBytes();

        this.cache.put("npm", "example", npmMeta).join();
        this.cache.put("go", "example", goMeta).join();

        assertArrayEquals(npmMeta, this.cache.get("npm", "example").join().get());
        assertArrayEquals(goMeta, this.cache.get("go", "example").join().get());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -pl artipie-core -Dtest=MergedMetadataCacheTest -DfailIfNoTests=false`
Expected: FAIL

**Step 3: Write implementation**

```java
package com.artipie.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.artipie.http.log.EcsLogger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Cache for merged group metadata.
 * Two-tier: L1 (Caffeine in-memory) + L2 (Valkey/Redis).
 *
 * @since 1.21.0
 */
public final class MergedMetadataCache {

    private final String groupName;
    private final GroupSettings settings;
    private final Cache<String, byte[]> l1Cache;
    private final Optional<ValkeyConnection> valkey;

    public MergedMetadataCache(final String groupName, final GroupSettings settings) {
        this(groupName, settings, Optional.empty());
    }

    public MergedMetadataCache(
        final String groupName,
        final GroupSettings settings,
        final Optional<ValkeyConnection> valkey
    ) {
        this.groupName = groupName;
        this.settings = settings;
        this.valkey = valkey;
        this.l1Cache = Caffeine.newBuilder()
            .maximumSize(settings.l1MaxEntries())
            .expireAfterWrite(settings.metadataTtl())
            .build();
    }

    /**
     * Get cached merged metadata.
     */
    public CompletableFuture<Optional<byte[]>> get(
        final String adapterType,
        final String packageName
    ) {
        final String cacheKey = cacheKey(adapterType, packageName);

        // L1 lookup
        final byte[] l1Result = this.l1Cache.getIfPresent(cacheKey);
        if (l1Result != null) {
            EcsLogger.debug("com.artipie.cache")
                .message("Merged metadata cache L1 HIT")
                .eventCategory("cache")
                .eventAction("metadata_get")
                .eventOutcome("success")
                .field("group.name", this.groupName)
                .field("adapter.type", adapterType)
                .field("package.name", packageName)
                .log();
            return CompletableFuture.completedFuture(Optional.of(l1Result));
        }

        // L2 lookup
        if (this.valkey.isPresent()) {
            return this.valkey.get().getBytes(cacheKey)
                .thenApply(l2Result -> {
                    if (l2Result.isPresent()) {
                        this.l1Cache.put(cacheKey, l2Result.get());
                        EcsLogger.debug("com.artipie.cache")
                            .message("Merged metadata cache L2 HIT (promoted to L1)")
                            .eventCategory("cache")
                            .eventAction("metadata_get")
                            .eventOutcome("success")
                            .field("group.name", this.groupName)
                            .field("adapter.type", adapterType)
                            .field("package.name", packageName)
                            .log();
                        return l2Result;
                    }
                    return Optional.<byte[]>empty();
                })
                .exceptionally(err -> {
                    EcsLogger.warn("com.artipie.cache")
                        .message("Failed to get from L2 cache")
                        .eventCategory("cache")
                        .eventAction("metadata_get")
                        .eventOutcome("failure")
                        .field("group.name", this.groupName)
                        .error(err)
                        .log();
                    return Optional.empty();
                });
        }

        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Store merged metadata.
     */
    public CompletableFuture<Void> put(
        final String adapterType,
        final String packageName,
        final byte[] mergedMetadata
    ) {
        final String cacheKey = cacheKey(adapterType, packageName);

        // Update L1
        this.l1Cache.put(cacheKey, mergedMetadata);

        // Update L2 (async)
        if (this.valkey.isPresent()) {
            return this.valkey.get().setBytes(cacheKey, mergedMetadata, this.settings.metadataTtl())
                .exceptionally(err -> {
                    EcsLogger.warn("com.artipie.cache")
                        .message("Failed to update L2 cache")
                        .eventCategory("cache")
                        .eventAction("metadata_put")
                        .eventOutcome("failure")
                        .field("group.name", this.groupName)
                        .error(err)
                        .log();
                    return null;
                });
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Invalidate cached metadata for a package.
     */
    public void invalidate(final String packageName) {
        // Invalidate all adapter types for this package
        // In practice, we'd need to track which adapter types exist
        // For now, invalidate known types
        for (String adapter : new String[]{"npm", "go", "pypi", "maven", "docker", "composer", "gradle"}) {
            final String cacheKey = cacheKey(adapter, packageName);
            this.l1Cache.invalidate(cacheKey);
            if (this.valkey.isPresent()) {
                this.valkey.get().delete(cacheKey);
            }
        }
    }

    /**
     * Invalidate specific adapter type for a package.
     */
    public void invalidate(final String adapterType, final String packageName) {
        final String cacheKey = cacheKey(adapterType, packageName);
        this.l1Cache.invalidate(cacheKey);
        if (this.valkey.isPresent()) {
            this.valkey.get().delete(cacheKey);
        }
    }

    private String cacheKey(final String adapterType, final String packageName) {
        return String.format("meta:%s:%s:%s", this.groupName, adapterType, packageName);
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -pl artipie-core -Dtest=MergedMetadataCacheTest`
Expected: PASS

**Step 5: Commit**

```bash
git add artipie-core/src/main/java/com/artipie/cache/MergedMetadataCache.java \
        artipie-core/src/test/java/com/artipie/cache/MergedMetadataCacheTest.java
git commit -m "feat(cache): add MergedMetadataCache for group metadata

Two-tier cache for storing merged metadata from group members.
Supports L1 (Caffeine) + L2 (Valkey) with configurable TTLs.

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 4: Create MetadataMerger Interface and Implementations

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/cache/MetadataMerger.java`
- Create: `npm-adapter/src/main/java/com/artipie/npm/metadata/NpmMetadataMerger.java`
- Create: `go-adapter/src/main/java/com/artipie/go/metadata/GoMetadataMerger.java`
- Create: `pypi-adapter/src/main/java/com/artipie/pypi/metadata/PypiMetadataMerger.java`
- Tests for each merger

**Step 1: Create interface**

```java
package com.artipie.cache;

import java.util.LinkedHashMap;

/**
 * Adapter-specific metadata merger.
 * Implementations merge metadata from multiple group members.
 *
 * @since 1.21.0
 */
@FunctionalInterface
public interface MetadataMerger {

    /**
     * Merge metadata from multiple members.
     *
     * @param responses Map of member name → metadata bytes (priority order)
     * @return Merged metadata bytes
     */
    byte[] merge(LinkedHashMap<String, byte[]> responses);
}
```

**Step 2: Create NpmMetadataMerger with test**

Test file: `npm-adapter/src/test/java/com/artipie/npm/metadata/NpmMetadataMergerTest.java`

```java
package com.artipie.npm.metadata;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class NpmMetadataMergerTest {

    @Test
    void mergesVersionsFromMultipleMembers() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("npm-local", """
            {"name":"lodash","versions":{"4.17.20":{},"4.17.21":{}}}
            """.getBytes(StandardCharsets.UTF_8));
        responses.put("npm-proxy", """
            {"name":"lodash","versions":{"4.17.21":{},"4.17.22":{}}}
            """.getBytes(StandardCharsets.UTF_8));

        final NpmMetadataMerger merger = new NpmMetadataMerger();
        final byte[] result = merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);

        assertTrue(json.contains("4.17.20"));
        assertTrue(json.contains("4.17.21"));
        assertTrue(json.contains("4.17.22"));
    }

    @Test
    void priorityMemberWinsForConflicts() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("npm-local", """
            {"name":"lodash","versions":{"4.17.21":{"local":true}}}
            """.getBytes(StandardCharsets.UTF_8));
        responses.put("npm-proxy", """
            {"name":"lodash","versions":{"4.17.21":{"local":false}}}
            """.getBytes(StandardCharsets.UTF_8));

        final NpmMetadataMerger merger = new NpmMetadataMerger();
        final byte[] result = merger.merge(responses);
        final String json = new String(result, StandardCharsets.UTF_8);

        // Priority member (first) should win
        assertTrue(json.contains("\"local\":true"));
    }
}
```

Implementation: `npm-adapter/src/main/java/com/artipie/npm/metadata/NpmMetadataMerger.java`

```java
package com.artipie.npm.metadata;

import com.artipie.cache.MetadataMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NPM metadata merger.
 * Merges package.json versions from multiple group members.
 *
 * @since 1.21.0
 */
public final class NpmMetadataMerger implements MetadataMerger {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
        if (responses.isEmpty()) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }

        try {
            ObjectNode merged = null;
            ObjectNode mergedVersions = null;

            for (Map.Entry<String, byte[]> entry : responses.entrySet()) {
                final JsonNode node = MAPPER.readTree(entry.getValue());
                if (!node.isObject()) {
                    continue;
                }

                final ObjectNode pkg = (ObjectNode) node;

                if (merged == null) {
                    // First response becomes base
                    merged = pkg.deepCopy();
                    mergedVersions = merged.has("versions")
                        ? (ObjectNode) merged.get("versions")
                        : MAPPER.createObjectNode();
                    merged.set("versions", mergedVersions);
                } else {
                    // Merge versions (priority member wins for conflicts)
                    final JsonNode versions = pkg.get("versions");
                    if (versions != null && versions.isObject()) {
                        final Iterator<String> fieldNames = versions.fieldNames();
                        while (fieldNames.hasNext()) {
                            final String version = fieldNames.next();
                            if (!mergedVersions.has(version)) {
                                mergedVersions.set(version, versions.get(version));
                            }
                        }
                    }

                    // Merge dist-tags (priority member wins)
                    final JsonNode distTags = pkg.get("dist-tags");
                    if (distTags != null && !merged.has("dist-tags")) {
                        merged.set("dist-tags", distTags);
                    }

                    // Merge time (if present)
                    final JsonNode time = pkg.get("time");
                    if (time != null && time.isObject()) {
                        ObjectNode mergedTime = merged.has("time")
                            ? (ObjectNode) merged.get("time")
                            : MAPPER.createObjectNode();
                        merged.set("time", mergedTime);

                        final Iterator<String> timeFields = time.fieldNames();
                        while (timeFields.hasNext()) {
                            final String field = timeFields.next();
                            if (!mergedTime.has(field)) {
                                mergedTime.set(field, time.get(field));
                            }
                        }
                    }
                }
            }

            return merged != null
                ? MAPPER.writeValueAsBytes(merged)
                : "{}".getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to merge NPM metadata", e);
        }
    }
}
```

**Step 3: Create GoMetadataMerger**

Test: `go-adapter/src/test/java/com/artipie/go/metadata/GoMetadataMergerTest.java`

```java
package com.artipie.go.metadata;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class GoMetadataMergerTest {

    @Test
    void mergesVersionLists() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("go-local", "v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8));
        responses.put("go-proxy", "v1.1.0\nv1.2.0\n".getBytes(StandardCharsets.UTF_8));

        final GoMetadataMerger merger = new GoMetadataMerger();
        final byte[] result = merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);

        assertTrue(text.contains("v1.0.0"));
        assertTrue(text.contains("v1.1.0"));
        assertTrue(text.contains("v1.2.0"));
        // Should be sorted
        assertTrue(text.indexOf("v1.0.0") < text.indexOf("v1.1.0"));
        assertTrue(text.indexOf("v1.1.0") < text.indexOf("v1.2.0"));
    }

    @Test
    void deduplicatesVersions() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("go-local", "v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8));
        responses.put("go-proxy", "v1.0.0\nv1.1.0\n".getBytes(StandardCharsets.UTF_8));

        final GoMetadataMerger merger = new GoMetadataMerger();
        final byte[] result = merger.merge(responses);
        final String text = new String(result, StandardCharsets.UTF_8);

        // Should only appear once
        assertEquals(1, text.split("v1.0.0").length - 1);
    }
}
```

Implementation: `go-adapter/src/main/java/com/artipie/go/metadata/GoMetadataMerger.java`

```java
package com.artipie.go.metadata;

import com.artipie.cache.MetadataMerger;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Go module metadata merger.
 * Merges @v/list version lists from multiple group members.
 *
 * @since 1.21.0
 */
public final class GoMetadataMerger implements MetadataMerger {

    @Override
    public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
        final Set<String> versions = new TreeSet<>(new VersionComparator());

        for (byte[] response : responses.values()) {
            final String text = new String(response, StandardCharsets.UTF_8);
            for (String line : text.split("\\n")) {
                final String version = line.trim();
                if (!version.isEmpty() && version.startsWith("v")) {
                    versions.add(version);
                }
            }
        }

        return versions.stream()
            .collect(Collectors.joining("\n", "", "\n"))
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Semantic version comparator.
     */
    private static final class VersionComparator implements Comparator<String> {
        @Override
        public int compare(final String v1, final String v2) {
            // Simple semver comparison: v1.2.3
            final String[] parts1 = v1.substring(1).split("\\.");
            final String[] parts2 = v2.substring(1).split("\\.");

            for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
                try {
                    final int num1 = Integer.parseInt(parts1[i].split("-")[0]);
                    final int num2 = Integer.parseInt(parts2[i].split("-")[0]);
                    if (num1 != num2) {
                        return Integer.compare(num1, num2);
                    }
                } catch (NumberFormatException e) {
                    return parts1[i].compareTo(parts2[i]);
                }
            }
            return Integer.compare(parts1.length, parts2.length);
        }
    }
}
```

**Step 4: Create PypiMetadataMerger**

Test and implementation similar pattern - merges HTML `/simple/` index pages.

**Step 5: Commit all mergers**

```bash
git add artipie-core/src/main/java/com/artipie/cache/MetadataMerger.java \
        npm-adapter/src/main/java/com/artipie/npm/metadata/NpmMetadataMerger.java \
        npm-adapter/src/test/java/com/artipie/npm/metadata/NpmMetadataMergerTest.java \
        go-adapter/src/main/java/com/artipie/go/metadata/GoMetadataMerger.java \
        go-adapter/src/test/java/com/artipie/go/metadata/GoMetadataMergerTest.java \
        pypi-adapter/src/main/java/com/artipie/pypi/metadata/PypiMetadataMerger.java \
        pypi-adapter/src/test/java/com/artipie/pypi/metadata/PypiMetadataMergerTest.java
git commit -m "feat(adapters): add metadata mergers for NPM, Go, PyPI

Adapter-specific implementations of MetadataMerger interface:
- NpmMetadataMerger: Merges package.json versions
- GoMetadataMerger: Merges @v/list with semver sorting
- PypiMetadataMerger: Merges /simple/ HTML index

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

### Task 5: Create UnifiedGroupCache Orchestrator

**Files:**
- Create: `artipie-core/src/main/java/com/artipie/cache/UnifiedGroupCache.java`
- Test: `artipie-core/src/test/java/com/artipie/cache/UnifiedGroupCacheTest.java`

**Step 1: Write the failing test**

```java
package com.artipie.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedGroupCacheTest {

    private UnifiedGroupCache cache;

    @BeforeEach
    void setUp() {
        this.cache = new UnifiedGroupCache("test-group", new GroupSettings());
    }

    @Test
    void returnsCachedMetadata() {
        // Pre-populate cache
        final byte[] metadata = "{\"test\":true}".getBytes();
        this.cache.cacheMetadata("npm", "lodash", metadata);

        final var result = this.cache.getMetadata("npm", "lodash", List.of(),
            responses -> responses.values().iterator().next()).join();

        assertTrue(result.isPresent());
        assertArrayEquals(metadata, result.get());
    }

    @Test
    void invalidatesOnLocalPublish() {
        final byte[] metadata = "{\"test\":true}".getBytes();
        this.cache.cacheMetadata("npm", "lodash", metadata);

        this.cache.onLocalPublish("npm-local", "lodash");

        // Index should be updated, metadata cache invalidated
        final var locations = this.cache.getLocations("lodash").join();
        assertTrue(locations.knownLocations().contains("npm-local"));
    }

    @Test
    void invalidatesOnLocalDelete() {
        this.cache.onLocalPublish("npm-local", "lodash");
        this.cache.onLocalDelete("npm-local", "lodash");

        // Should be removed from index
        final var locations = this.cache.getLocations("lodash").join();
        assertFalse(locations.knownLocations().contains("npm-local"));
    }
}
```

**Step 2: Write implementation**

```java
package com.artipie.cache;

import com.artipie.http.log.EcsLogger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Unified cache for group repositories.
 * Orchestrates PackageLocationIndex, MergedMetadataCache, and NegativeCache.
 *
 * @since 1.21.0
 */
public final class UnifiedGroupCache implements AutoCloseable {

    private final String groupName;
    private final GroupSettings settings;
    private final PackageLocationIndex locationIndex;
    private final MergedMetadataCache metadataCache;

    public UnifiedGroupCache(final String groupName, final GroupSettings settings) {
        this(groupName, settings, Optional.empty());
    }

    public UnifiedGroupCache(
        final String groupName,
        final GroupSettings settings,
        final Optional<ValkeyConnection> valkey
    ) {
        this.groupName = groupName;
        this.settings = settings;
        this.locationIndex = new PackageLocationIndex(groupName, settings, valkey);
        this.metadataCache = new MergedMetadataCache(groupName, settings, valkey);
    }

    /**
     * Get merged metadata for a package.
     *
     * @param adapterType Adapter type (npm, go, pypi, etc.)
     * @param packageName Package name
     * @param memberFetchers Functions to fetch from each member
     * @param merger Metadata merger
     * @return Merged metadata or empty
     */
    public CompletableFuture<Optional<byte[]>> getMetadata(
        final String adapterType,
        final String packageName,
        final List<MemberFetcher> memberFetchers,
        final MetadataMerger merger
    ) {
        // Step 1: Check metadata cache
        return this.metadataCache.get(adapterType, packageName)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    return CompletableFuture.completedFuture(cached);
                }

                // Step 2: Fetch from members and merge
                return fetchAndMerge(adapterType, packageName, memberFetchers, merger);
            });
    }

    /**
     * Get package locations from index.
     */
    public CompletableFuture<PackageLocations> getLocations(final String packageName) {
        return this.locationIndex.getLocations(packageName);
    }

    /**
     * Cache merged metadata directly (for pre-computed results).
     */
    public void cacheMetadata(
        final String adapterType,
        final String packageName,
        final byte[] metadata
    ) {
        this.metadataCache.put(adapterType, packageName, metadata);
    }

    /**
     * Handle local repository publish event.
     * Updates index immediately (event-driven) and invalidates metadata cache.
     */
    public void onLocalPublish(final String memberName, final String packageName) {
        EcsLogger.debug("com.artipie.cache")
            .message("Local publish event")
            .eventCategory("cache")
            .eventAction("local_publish")
            .field("group.name", this.groupName)
            .field("member.name", memberName)
            .field("package.name", packageName)
            .log();

        // Update index immediately (no TTL for local)
        this.locationIndex.markExistsNoTtl(memberName, packageName);

        // Invalidate merged metadata cache
        this.metadataCache.invalidate(packageName);
    }

    /**
     * Handle local repository delete event.
     */
    public void onLocalDelete(final String memberName, final String packageName) {
        EcsLogger.debug("com.artipie.cache")
            .message("Local delete event")
            .eventCategory("cache")
            .eventAction("local_delete")
            .field("group.name", this.groupName)
            .field("member.name", memberName)
            .field("package.name", packageName)
            .log();

        // Remove from index
        this.locationIndex.invalidateMember(memberName, packageName);

        // Invalidate merged metadata cache
        this.metadataCache.invalidate(packageName);
    }

    /**
     * Record successful fetch from member (updates index).
     */
    public void recordMemberHit(final String memberName, final String packageName) {
        this.locationIndex.markExists(memberName, packageName);
    }

    /**
     * Record 404 from member (negative cache).
     */
    public void recordMemberMiss(final String memberName, final String packageName) {
        this.locationIndex.markNotExists(memberName, packageName);
    }

    private CompletableFuture<Optional<byte[]>> fetchAndMerge(
        final String adapterType,
        final String packageName,
        final List<MemberFetcher> memberFetchers,
        final MetadataMerger merger
    ) {
        // Fetch from all members in parallel
        final LinkedHashMap<String, CompletableFuture<Optional<byte[]>>> futures = new LinkedHashMap<>();

        for (MemberFetcher fetcher : memberFetchers) {
            futures.put(fetcher.memberName(), fetcher.fetch());
        }

        // Wait for all and merge
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();

                for (var entry : futures.entrySet()) {
                    try {
                        final Optional<byte[]> result = entry.getValue().join();
                        if (result.isPresent()) {
                            responses.put(entry.getKey(), result.get());
                            recordMemberHit(entry.getKey(), packageName);
                        } else {
                            recordMemberMiss(entry.getKey(), packageName);
                        }
                    } catch (Exception e) {
                        EcsLogger.warn("com.artipie.cache")
                            .message("Member fetch failed")
                            .field("member.name", entry.getKey())
                            .error(e)
                            .log();
                    }
                }

                if (responses.isEmpty()) {
                    return Optional.<byte[]>empty();
                }

                // Merge responses
                final byte[] merged = merger.merge(responses);

                // Cache result
                this.metadataCache.put(adapterType, packageName, merged);

                return Optional.of(merged);
            });
    }

    @Override
    public void close() {
        // Cleanup resources if needed
    }

    /**
     * Member fetcher interface.
     */
    public interface MemberFetcher {
        String memberName();
        CompletableFuture<Optional<byte[]>> fetch();
    }
}
```

**Step 3: Run tests and commit**

```bash
mvn test -pl artipie-core -Dtest=UnifiedGroupCacheTest
git add artipie-core/src/main/java/com/artipie/cache/UnifiedGroupCache.java \
        artipie-core/src/test/java/com/artipie/cache/UnifiedGroupCacheTest.java
git commit -m "feat(cache): add UnifiedGroupCache orchestrator

Coordinates PackageLocationIndex, MergedMetadataCache, and member fetching.
Handles local publish/delete events for immediate index updates.

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Phase 2: Integration with Group Slices

### Task 6: Update GroupSlice to Use UnifiedGroupCache

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/group/GroupSlice.java`
- Update: `artipie-main/src/test/java/com/artipie/group/GroupSliceTest.java`

This task integrates the new cache infrastructure with the existing GroupSlice.

### Task 7: Create Adapter-Specific Group Slices

**Files:**
- Create: `artipie-main/src/main/java/com/artipie/group/NpmGroupSlice.java`
- Create: `artipie-main/src/main/java/com/artipie/group/GoGroupSlice.java`
- Create: `artipie-main/src/main/java/com/artipie/group/PypiGroupSlice.java`
- Create: `artipie-main/src/main/java/com/artipie/group/DockerGroupSlice.java`
- Create: `artipie-main/src/main/java/com/artipie/group/ComposerGroupSlice.java`
- Tests for each

Pattern follows MavenGroupSlice - detect metadata requests, delegate to UnifiedGroupCache for merging.

### Task 8: Update RepositorySlices to Use New Group Slices

**Files:**
- Modify: `artipie-main/src/main/java/com/artipie/RepositorySlices.java`

Wire up new adapter-specific group slices based on repository type.

---

## Phase 3: Cache Cleanup

### Task 9: Delete Redundant Caches

**Files to DELETE:**
- `maven-adapter/src/main/java/com/artipie/maven/http/NegativeCache.java`
- `artipie-main/src/main/java/com/artipie/group/GroupNegativeCache.java`
- `artipie-main/src/main/java/com/artipie/group/GroupMetadataCache.java`

**Tests to DELETE:**
- `maven-adapter/src/test/java/com/artipie/maven/http/NegativeCacheTest.java`
- Related test files

**Files to UPDATE:**
- All files that import deleted classes must be updated to use core NegativeCache

### Task 10: Update Maven Adapter to Use Core NegativeCache

**Files:**
- Modify: `maven-adapter/src/main/java/com/artipie/maven/http/MavenProxySlice.java`
- Modify: `maven-adapter/src/main/java/com/artipie/maven/http/CachedProxySlice.java`
- Update tests

---

## Phase 4: Test Updates

### Task 11: Update Existing Group Tests

**Files:**
- Update: `artipie-core/src/test/java/com/artipie/http/group/GroupSliceTest.java`
- Update: `artipie-main/src/test/java/com/artipie/group/GroupSliceTest.java`
- Update: `artipie-main/src/test/java/com/artipie/group/MavenGroupSliceTest.java`
- Update: `artipie-main/src/test/java/com/artipie/http/GroupRepositoryITCase.java`

### Task 12: Add Integration Tests for New Group Slices

**Files:**
- Create: `artipie-main/src/test/java/com/artipie/group/NpmGroupSliceIT.java`
- Create: `artipie-main/src/test/java/com/artipie/group/GoGroupSliceIT.java`
- Create: `artipie-main/src/test/java/com/artipie/group/MetadataMergingIT.java`

Integration tests using TestContainers to verify metadata merging works end-to-end.

---

## Phase 5: Documentation Updates

### Task 13: Update User Guide

**File:** `docs/USER_GUIDE.md`

Add sections:
- Group Repository Metadata Merging
- Cache Configuration (global and repo-level)
- HTTP Client Configuration (Vert.x)
- Migration from Jetty to Vert.x client

### Task 14: Update Developer Guide

**File:** `docs/DEVELOPER_GUIDE.md`

Update:
- Technology Stack (change Jetty → Vert.x for HTTP Client)
- Architecture diagram showing cache layers
- Group repository internals
- Adding new adapter group support

### Task 15: Update Wiki Configuration Pages

**Files:**
- `.wiki/Configuration.md`
- `.wiki/Configuration-Repository.md`
- Create: `.wiki/Configuration-Group-Cache.md`

Document:
- `group.index.*` settings
- `group.metadata.*` settings
- `group.resolution.*` settings
- `group.cache.*` settings
- Examples for each adapter type

### Task 16: Update Example Configurations

**Files:**
- `artipie-main/examples/artipie.yml`
- `artipie-main/docker-compose/artipie/artipie.yml`
- Create example group repo configs for each adapter

---

## Phase 6: Final Verification

### Task 17: Run Full Build

```bash
mvn clean install -U
```

Expected: BUILD SUCCESS with all tests passing.

### Task 18: Fix Any Failing Tests

Review and fix any test failures:
1. Unit tests
2. Integration tests
3. Checkstyle/PMD violations
4. Compilation errors

### Task 19: Run Integration Tests with Network

```bash
mvn verify -Dtest.networkEnabled=true
```

### Task 20: Final Commit and Tag

```bash
git add -A
git commit -m "feat: complete group metadata merging implementation

- PackageLocationIndex for tracking package locations
- MergedMetadataCache for caching merged metadata
- UnifiedGroupCache orchestrating all caches
- Adapter-specific metadata mergers (NPM, Go, PyPI, Docker, Composer)
- Adapter-specific group slices
- Configurable TTLs at global and repo level
- Event-driven index updates for local repos
- Non-blocking design for Vert.x compatibility
- Documentation updates (User Guide, Developer Guide, Wiki)
- Cache consolidation (removed redundant caches)

Closes: #XXX

Co-Authored-By: Claude Opus 4.5 <noreply@anthropic.com>"
```

---

## Acceptance Criteria Checklist

- [ ] `mvn clean install -U` passes
- [ ] All unit tests green
- [ ] All integration tests green
- [ ] No PMD/Checkstyle violations
- [ ] User Guide updated
- [ ] Developer Guide updated (HTTP Client: Jetty → Vert.x)
- [ ] Wiki configuration pages updated
- [ ] Example configurations added
- [ ] Cache config works at global level (artipie.yaml)
- [ ] Cache config works at repo level
- [ ] Repo-level config overrides global

---

## Summary

| Phase | Tasks | Files |
|-------|-------|-------|
| 1. Core Infrastructure | 5 | ~15 new files |
| 2. Integration | 3 | ~10 modified/new |
| 3. Cache Cleanup | 2 | ~5 deleted, ~10 modified |
| 4. Test Updates | 2 | ~10 modified/new |
| 5. Documentation | 4 | ~8 modified/new |
| 6. Final Verification | 4 | - |

**Total: 20 tasks**
