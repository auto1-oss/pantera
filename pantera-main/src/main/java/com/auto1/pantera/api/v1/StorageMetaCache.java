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
package com.auto1.pantera.api.v1;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;

/**
 * Caffeine-backed cache for storage-level file metadata (size, modified
 * timestamp) used by the tree-handler fallback path.
 *
 * <p>The tree endpoint first tries a DB batch query. For repo types where
 * the DB {@code name} column does not match individual file paths (Go, npm,
 * PyPI, Docker, Helm, Debian) the query returns no rows and we fall back to
 * {@link com.auto1.pantera.asto.Storage#metadata(com.auto1.pantera.asto.Key)}.
 * Typical browse sessions re-visit sibling directories, hitting the same
 * storage keys repeatedly. This cache avoids N S3 HEADs per page view.</p>
 *
 * <p>Keys are {@code repoName + "|" + path} (pipe is reserved in neither
 * component). Values are {@link Entry} records carrying size and ISO-8601
 * modified timestamp, both of which may be {@code null} when the storage
 * does not provide them.</p>
 *
 * @since 2.2.0
 */
public final class StorageMetaCache {

    /**
     * Max entries — bounded LRU to prevent memory blowup under bot crawls.
     */
    public static final int MAX_ENTRIES = 10_000;

    /**
     * Entries expire after 30 minutes (write-based).
     */
    public static final Duration TTL = Duration.ofMinutes(30);

    /**
     * Cached metadata entry.
     * @param size File size in bytes, may be {@code null}
     * @param modifiedIso ISO-8601 timestamp string, may be {@code null}
     */
    public record Entry(Long size, String modifiedIso) {}

    /**
     * Backing Caffeine cache.
     */
    private final Cache<String, Entry> cache;

    /**
     * Default constructor — uses production limits.
     */
    public StorageMetaCache() {
        this(MAX_ENTRIES, TTL);
    }

    /**
     * Package-private constructor for tests (tune TTL / size).
     * @param maxEntries Maximum cache entries before LRU eviction
     * @param ttl Time-to-live for cache entries (write-based)
     */
    StorageMetaCache(final int maxEntries, final Duration ttl) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(ttl)
            .build();
    }

    /**
     * Look up a cached metadata entry.
     * @param repoName Repository name
     * @param path Repo-relative file path
     * @return {@link Optional} carrying the entry, or empty on cache miss
     */
    public Optional<Entry> get(final String repoName, final String path) {
        return Optional.ofNullable(this.cache.getIfPresent(key(repoName, path)));
    }

    /**
     * Store a metadata entry.
     * @param repoName Repository name
     * @param path Repo-relative file path
     * @param size File size in bytes (may be {@code null})
     * @param modifiedIso ISO-8601 modified timestamp (may be {@code null})
     */
    public void put(final String repoName, final String path,
        final Long size, final String modifiedIso) {
        this.cache.put(key(repoName, path), new Entry(size, modifiedIso));
    }

    /**
     * Evict a single cache entry.
     * @param repoName Repository name
     * @param path Repo-relative file path
     */
    public void invalidate(final String repoName, final String path) {
        this.cache.invalidate(key(repoName, path));
    }

    /**
     * Evict all entries whose key starts with {@code repoName|pathPrefix}.
     * Used for folder deletes where any number of child paths may be cached.
     * The cache is small (≤ {@link #MAX_ENTRIES} entries) so an O(n) scan
     * is acceptable.
     * @param repoName Repository name
     * @param pathPrefix Repo-relative path prefix to evict
     */
    public void invalidatePrefix(final String repoName, final String pathPrefix) {
        final String full = repoName + "|" + (pathPrefix == null ? "" : pathPrefix);
        this.cache.asMap().keySet().removeIf(k -> k.startsWith(full));
    }

    /**
     * Run pending Caffeine maintenance (expiry, eviction) synchronously.
     * Package-private for tests — allows deterministic assertions on TTL
     * and LRU eviction without relying on scheduler timing.
     */
    void cleanUp() {
        this.cache.cleanUp();
    }

    /**
     * Build the composite cache key.
     * @param repoName Repository name
     * @param path Repo-relative file path
     * @return Cache key string
     */
    private static String key(final String repoName, final String path) {
        return repoName + "|" + (path == null ? "" : path);
    }
}
