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
package com.auto1.pantera.http.cache;

/**
 * Global accessor for the single shared {@link NegativeCache} bean.
 *
 * <p>Set once at startup from {@code RepositorySlices}; adapters obtain it via
 * {@link #sharedCache()}. Falls back to a default instance if the shared cache
 * has not been initialized (used by tests and early startup).
 *
 * @since 1.20.13
 */
public final class NegativeCacheRegistry {

    private static final NegativeCacheRegistry INSTANCE = new NegativeCacheRegistry();

    private static final NegativeCache FALLBACK = createFallback();

    private volatile NegativeCache shared;

    private NegativeCacheRegistry() {
    }

    public static NegativeCacheRegistry instance() {
        return INSTANCE;
    }

    /**
     * Set the single shared NegativeCache bean. Called once at startup.
     * @param cache Shared NegativeCache instance
     */
    public void setSharedCache(final NegativeCache cache) {
        this.shared = cache;
    }

    /**
     * Check whether a shared cache has been explicitly set.
     * @return true if the shared cache is initialized
     */
    public boolean isSharedCacheSet() {
        return this.shared != null;
    }

    /**
     * Get the shared NegativeCache bean. Returns a default fallback if the
     * shared bean has not been initialized yet (tests, early startup).
     * @return Shared NegativeCache
     */
    public NegativeCache sharedCache() {
        final NegativeCache s = this.shared;
        if (s != null) {
            return s;
        }
        return FALLBACK;
    }

    /**
     * Clear the shared reference (for testing).
     */
    public void clear() {
        this.shared = null;
    }

    /**
     * Invalidate every negative-cache entry whose canonical artifact name
     * matches {@code artifactName} (exact or parent-prefix; see
     * {@link NegativeCache#invalidateByArtifactName(String)}). Called by
     * every adapter's upload / publish path so a 404 cached before the
     * artifact existed does not keep shadowing it for the negative TTL.
     *
     * <p>The invalidation is published on {@code CacheInvalidationPubSub}
     * (via {@link NegativeCache#invalidateByArtifactName}) so peer
     * instances in a multi-node cluster also drop their L1 entries; the
     * uploading instance's L1 + the shared L2 are cleared synchronously
     * before this method returns.
     *
     * <p>Never throws — an exception in cache cleanup must not break the
     * user-facing upload.
     *
     * @param repoType  Repository type (e.g. {@code "maven-proxy"},
     *                  {@code "npm-hosted"}) for logging only.
     * @param artifactName Canonical artifact name as the cache stores it
     *                  (e.g. {@code "com/google/guava/guava"} for Maven,
     *                  {@code "lodash"} for npm). {@code null} or empty
     *                  → no-op.
     * @return number of L1 entries invalidated on this instance (0 on null/empty input or on failure).
     */
    public int invalidateAfterUpload(final String repoType, final String artifactName) {
        if (artifactName == null || artifactName.isEmpty()) {
            return 0;
        }
        try {
            final int count = this.sharedCache().invalidateByArtifactName(artifactName);
            if (count > 0) {
                com.auto1.pantera.http.log.EcsLogger.info("com.auto1.pantera.cache")
                    .message("Negative-cache invalidated after upload (n=" + count + ")")
                    .eventCategory("database")
                    .eventAction("neg_cache_invalidate_on_upload")
                    .eventOutcome("success")
                    .field("package.name", artifactName)
                    .field("repository.type", repoType == null ? "unknown" : repoType)
                    .field("log.source", "application")
                    .log();
            }
            return count;
        } catch (final RuntimeException ex) {
            com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cache")
                .message("Negative-cache invalidation failed; entry will expire via TTL")
                .eventCategory("database")
                .eventAction("neg_cache_invalidate_on_upload")
                .eventOutcome("failure")
                .field("package.name", artifactName)
                .field("repository.type", repoType == null ? "unknown" : repoType)
                .error(ex)
                .field("log.source", "application")
                .log();
            return 0;
        }
    }

    /**
     * Batched counterpart to {@link #invalidateAfterUpload(String, String)}:
     * clears every negative entry matching ANY of {@code artifactNames} in a
     * single L1 scan. Used by the async {@code DbConsumer} after a batch of
     * artifact rows is committed (both hosted publishes and proxy fetch-and-
     * store), so a package that 404'd before it was cached stops returning a
     * stale 404 once it lands in the index — closing the proxy-ingestion
     * invalidation gap that per-adapter hosted upload slices never covered.
     *
     * <p>Never throws — cache cleanup must not break the DB-consumer batch.
     *
     * @param artifactNames Canonical artifact names just committed
     *                      (deduplicated by the caller); null/empty → no-op
     * @return number of L1 entries invalidated on this instance
     */
    public int invalidateAfterUploadBatch(final java.util.Collection<String> artifactNames) {
        if (artifactNames == null || artifactNames.isEmpty()) {
            return 0;
        }
        try {
            final int count = this.sharedCache().invalidateByArtifactNames(artifactNames);
            if (count > 0) {
                com.auto1.pantera.http.log.EcsLogger.info("com.auto1.pantera.cache")
                    .message("Negative-cache batch-invalidated after ingestion (n=" + count + ")")
                    .eventCategory("database")
                    .eventAction("neg_cache_invalidate_on_upload")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
            return count;
        } catch (final RuntimeException ex) {
            com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cache")
                .message("Negative-cache batch invalidation failed; entries will expire via TTL")
                .eventCategory("database")
                .eventAction("neg_cache_invalidate_on_upload")
                .eventOutcome("failure")
                .error(ex)
                .field("log.source", "application")
                .log();
            return 0;
        }
    }

    private static NegativeCache createFallback() {
        return new NegativeCache(new com.auto1.pantera.cache.NegativeCacheConfig());
    }
}
