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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.log.EcsLogger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global accessor for every go-proxy repository's storage-backed
 * {@code @v/list} / {@code @latest} base-document cache (WS4-go.2).
 *
 * <p>Mirrors {@code com.auto1.pantera.http.cache.NegativeCacheRegistry}
 * and {@code com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry}:
 * each go-proxy repository's {@link CachedProxySlice} registers its
 * backing {@link Storage} here at construction, and {@link GoUploadSlice}
 * (the hosted "go" upload path) invalidates the module's cached base
 * documents in every registered proxy repo after a successful publish —
 * closing the same "publish hidden behind the cache TTL" gap those two
 * registries already close for the negative cache and cooldown-filtered
 * envelopes. Without this, a hosted publish inside a {@code go} group
 * (hosted member + go-proxy member) would stay invisible to
 * {@code @v/list}/{@code @latest} resolution for up to the 12h TTL, even
 * though the negative-cache / filtered-metadata entries were correctly
 * cleared.</p>
 *
 * <p>Hosted and go-proxy repositories always use separate {@link Storage}
 * instances, so a hosted publish cannot reach the proxy's cache through
 * a shared storage reference — this registry is the cross-repository
 * bridge.</p>
 *
 * @since 2.3.0
 */
final class GoMetadataCacheRegistry {

    /**
     * Process-wide singleton.
     */
    private static final GoMetadataCacheRegistry INSTANCE = new GoMetadataCacheRegistry();

    /**
     * Backing storage per registered go-proxy repository, keyed by
     * repository name.
     */
    private final Map<String, Storage> storages = new ConcurrentHashMap<>();

    private GoMetadataCacheRegistry() {
    }

    /**
     * @return the shared registry
     */
    static GoMetadataCacheRegistry instance() {
        return INSTANCE;
    }

    /**
     * Register (or replace) the backing storage for a go-proxy
     * repository's metadata base cache. Called once per
     * {@link CachedProxySlice} construction.
     *
     * @param repoName Repository name
     * @param storage Backing storage for the repository's base cache
     */
    void register(final String repoName, final Storage storage) {
        this.storages.put(repoName, storage);
    }

    /**
     * Clear the registry (tests only).
     */
    void clear() {
        this.storages.clear();
    }

    /**
     * Evict the cached {@code @v/list} / {@code @latest} base documents
     * for {@code module} from every registered go-proxy repository so a
     * hosted publish is not hidden behind the 12h TTL. Never throws —
     * cache cleanup must not break the upload response.
     *
     * @param module Module path as it appears in the storage key (the
     *               escaped form — matches what {@code CachedProxySlice}
     *               keys its cache entries with)
     * @return Future that completes once every registered repository has
     *         been checked; failures are logged and swallowed per-repo
     *         so one unreachable storage cannot block the others.
     */
    CompletableFuture<Void> invalidateAfterUpload(final String module) {
        if (module == null || module.isEmpty() || this.storages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final CompletableFuture<?>[] futures = this.storages.entrySet().stream()
            .map(entry -> evictRepo(entry.getKey(), entry.getValue(), module))
            .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static CompletableFuture<Void> evictRepo(
        final String repoName, final Storage storage, final String module
    ) {
        final Key list = new Key.From(module + "/@v/list");
        final Key latest = new Key.From(module + "/@latest");
        return evictKey(storage, list)
            .thenCompose(ignored -> evictKey(storage, latest))
            .exceptionally(err -> {
                EcsLogger.warn("com.auto1.pantera.http")
                    .message("Go metadata base-cache invalidation failed; entry will expire via TTL")
                    .eventCategory("file")
                    .eventAction("metadata_base_invalidate_on_upload")
                    .eventOutcome("failure")
                    .field("repository.name", repoName)
                    .field("package.name", module)
                    .error(err)
                    .field("log.source", "application")
                    .log();
                return null;
            });
    }

    private static CompletableFuture<Void> evictKey(final Storage storage, final Key key) {
        return storage.exists(key).thenCompose(exists -> {
            if (exists) {
                return storage.delete(key);
            }
            return CompletableFuture.completedFuture(null);
        });
    }
}
