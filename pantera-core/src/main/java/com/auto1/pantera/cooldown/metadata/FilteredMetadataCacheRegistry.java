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

/**
 * Global accessor for the single shared {@link FilteredMetadataCache} bean.
 *
 * <p>Mirrors {@link com.auto1.pantera.http.cache.NegativeCacheRegistry} so
 * adapter upload paths can drop stale cooldown-filtered envelopes
 * without crossing module boundaries. Set once at startup by
 * {@code CooldownSupport} when a real metadata cache is wired; falls
 * back to a no-op when the cache has not been initialized (tests,
 * early startup, deployments without cooldown).
 *
 * @since 2.2.0
 */
public final class FilteredMetadataCacheRegistry {

    private static final FilteredMetadataCacheRegistry INSTANCE =
        new FilteredMetadataCacheRegistry();

    private volatile FilteredMetadataCache shared;

    private FilteredMetadataCacheRegistry() {
    }

    public static FilteredMetadataCacheRegistry instance() {
        return INSTANCE;
    }

    /**
     * Set the single shared {@link FilteredMetadataCache} bean. Called
     * once at startup from {@code CooldownSupport.create()} alongside
     * the existing {@code jdbc.setEnvelopeInvalidator(metadataCache)}
     * wiring.
     *
     * @param cache Shared FilteredMetadataCache instance, or {@code null}
     *              to clear (tests).
     */
    public void setSharedCache(final FilteredMetadataCache cache) {
        this.shared = cache;
    }

    /**
     * The single shared {@link FilteredMetadataCache} bean, or {@code null}
     * when cooldown metadata caching has not been wired (tests, early
     * startup, deployments without cooldown).
     *
     * <p>Adapter serve paths that want to cache their own filtered/rewritten
     * metadata bytes (e.g. the PyPI {@code /simple/} handler) read the shared
     * instance through this accessor so their entries live in the SAME cache
     * the invalidation hooks target — {@link #invalidateAfterUpload}, {@link
     * #invalidateAfterProxyRefresh}, the JDBC block/unblock envelope
     * invalidator, cross-instance pub/sub, and the policy-change wipe all
     * operate on this one bean, so a per-format cache reusing it inherits
     * every coordination path for free instead of drifting out of sync.</p>
     *
     * @return Shared cache instance, or {@code null} if uninitialised.
     */
    public FilteredMetadataCache sharedCache() {
        return this.shared;
    }

    /**
     * Clear the shared reference (for testing).
     */
    public void clear() {
        this.shared = null;
    }

    /**
     * Invalidate every cached cooldown-filtered envelope whose package
     * name matches {@code packageName}. Called by every adapter's
     * upload / publish path so a stale envelope (cached before the new
     * version existed) does not keep hiding the upload for the static
     * cache TTL.
     *
     * <p>Matches on the package-name segment of the cache key shape
     * {@code metadata:{repoType}:{repoName}:{packageName}}, so an
     * upload to {@code local_b} also drops envelopes cached for
     * {@code group_a} (which contains {@code local_b} as a member).
     *
     * <p>The invalidation is also published on the
     * {@code cooldown-envelope} pub/sub channel (via the existing
     * {@code CacheInvalidationPubSub} wiring registered in
     * {@code CooldownSupport}), so peer instances drop their L1
     * entries too.
     *
     * <p>No-op if the shared cache has not been initialized (tests,
     * deployments without cooldown, early startup). Never throws —
     * a cache cleanup failure must never break an upload.
     *
     * @param repoType Repository type of the uploaded artifact, used
     *                 for logging only.
     * @param packageName Canonical package name as the cache stores it
     *                 (e.g. {@code "com.google.guava.guava"} for Maven
     *                 events, {@code "lodash"} for npm). {@code null}
     *                 or empty → no-op.
     * @return number of L1 entries invalidated on this instance.
     */
    public int invalidateAfterUpload(final String repoType, final String packageName) {
        return this.invalidate(repoType, packageName, "upload", "envelope_invalidate_on_upload");
    }

    /**
     * Invalidate every cached cooldown-filtered envelope whose package name
     * matches {@code packageName} after a **proxy** refresh — a stale-while-
     * revalidate background fetch (or any other post-cache-write path) that
     * pulled a genuinely-changed upstream packument/index. Without this call
     * a version published upstream stays hidden behind the previously
     * cached filtered envelope until its static TTL expires (WS5.2):
     * publish-date registries and cooldown decisions age, but the
     * MATERIALISED filtered bytes do not until told to.
     *
     * <p>Same mechanics as {@link #invalidateAfterUpload(String, String)} —
     * matches on the package-name segment of the cache key, published on
     * {@code CacheInvalidationPubSub} so peer nodes drop their L1 too — the
     * only difference is the log action, kept distinct so operators can
     * tell "an upload changed this" from "a proxy refresh changed this" in
     * the ECS log stream.
     *
     * @param repoType Repository type of the refreshed proxy, for logging only.
     * @param packageName Canonical package name as the cache stores it.
     *                    {@code null} or empty → no-op.
     * @return number of L1 entries invalidated on this instance.
     */
    public int invalidateAfterProxyRefresh(final String repoType, final String packageName) {
        return this.invalidate(repoType, packageName, "refresh", "envelope_invalidate_on_refresh");
    }

    private int invalidate(
        final String repoType, final String packageName,
        final String verb, final String eventAction
    ) {
        if (packageName == null || packageName.isEmpty()) {
            return 0;
        }
        final FilteredMetadataCache cache = this.shared;
        if (cache == null) {
            return 0;
        }
        try {
            final int count = cache.invalidateByPackageName(packageName);
            if (count > 0) {
                com.auto1.pantera.http.log.EcsLogger.info("com.auto1.pantera.cooldown.metadata")
                    .message("Filtered-metadata envelope invalidated after " + verb + " (n=" + count + ")")
                    .eventCategory("database")
                    .eventAction(eventAction)
                    .eventOutcome("success")
                    .field("package.name", packageName)
                    .field("repository.type", repoType == null ? "unknown" : repoType)
                    .field("log.source", "application")
                    .log();
            }
            return count;
        } catch (final RuntimeException ex) {
            com.auto1.pantera.http.log.EcsLogger.warn("com.auto1.pantera.cooldown.metadata")
                .message("Filtered-metadata envelope invalidation failed; entry will expire via TTL")
                .eventCategory("database")
                .eventAction(eventAction)
                .eventOutcome("failure")
                .field("package.name", packageName)
                .field("repository.type", repoType == null ? "unknown" : repoType)
                .error(ex)
                .field("log.source", "application")
                .log();
            return 0;
        }
    }
}
