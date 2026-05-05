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
package com.auto1.pantera.adapters.npm;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.cache.CacheWriteCallbackRegistry;
import com.auto1.pantera.http.cache.CacheWriteEvent;
import com.auto1.pantera.http.log.EcsLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Bridge that fires {@link CacheWriteEvent} for npm-proxy cache-miss writes.
 *
 * <p>The other proxy adapters (maven, pypi, etc.) all flow through
 * {@code BaseCachedProxySlice} / {@code ProxyCacheWriter}, which fire the
 * registry's shared callback automatically. The npm proxy is structurally
 * different — it persists assets directly through {@code RxNpmProxyStorage}
 * — so we install a one-line bridge from {@code NpmProxy.cacheWriteHook} to
 * the registry. Without this, speculative pre-fetch wired in commit
 * {@code e9eb477c7} parses the npm tarball but is never invoked because no
 * {@code CacheWriteEvent} ever fires for npm.</p>
 *
 * <p>Materialises the freshly-saved asset bytes from {@link Storage} to a
 * dedicated temp file and fires the event synchronously, mirroring
 * {@code ProxyCacheWriter.materialiseCallbackTempFile} +
 * {@code BaseCachedProxySlice.fireOnCacheWrite}. The file is deleted after
 * the consumer returns; consumers that need the bytes past that point copy
 * eagerly inside their callback, per {@link CacheWriteEvent} contract.</p>
 *
 * <p>Skip when no callback is installed (registry returns the no-op sentinel)
 * — no point materialising a temp file just to throw it away.</p>
 *
 * @since 2.2.0
 */
public final class NpmCacheWriteBridge {

    /** Storage to read the just-saved asset bytes from. */
    private final Storage storage;

    /** Repo name carried into the {@link CacheWriteEvent}. */
    private final String repoName;

    /**
     * Ctor.
     *
     * @param storage Storage to read the just-saved asset bytes from
     * @param repoName Repo name carried into the event
     */
    public NpmCacheWriteBridge(final Storage storage, final String repoName) {
        this.storage = storage;
        this.repoName = repoName;
    }

    /**
     * Build a hook for {@code NpmProxy} that fires {@code CacheWriteEvent} on
     * each cache-miss save. Skips when no callback is installed (test boots,
     * no PrefetchDispatcher).
     *
     * @return Consumer suitable for {@code NpmProxy}'s {@code cacheWriteHook}
     */
    public Consumer<String> hook() {
        return this::onAssetSaved;
    }

    /**
     * Read the saved asset bytes from storage, materialise to a temp file,
     * fire the event, and delete the temp file. Any throwable is logged and
     * swallowed — never propagates to the serve path.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void onAssetSaved(final String assetPath) {
        final long entryNs = System.nanoTime();
        final Consumer<CacheWriteEvent> shared =
            CacheWriteCallbackRegistry.instance().sharedCallback();
        if (CacheWriteCallbackRegistry.instance().isNoOp(shared)) {
            return;
        }
        EcsLogger.debug("com.auto1.pantera.adapters.npm")
            .message("npm cache-write bridge: firing CacheWriteEvent")
            .eventCategory("process")
            .eventAction("cache_write_event")
            .field("repository.name", this.repoName)
            .field("url.path", assetPath)
            .log();
        final Key key = new Key.From(assetPath);
        final long readNs = System.nanoTime();
        this.storage.value(key)
            .thenCompose(content -> content.asBytesFuture())
            .whenComplete((b, e) -> recordPhase("bridge_storage_read", readNs))
            .thenAccept(bytes -> {
                final long writeNs = System.nanoTime();
                Path tmp = null;
                try {
                    tmp = Files.createTempFile("pantera-npm-prefetch-", ".tgz");
                    Files.write(tmp, bytes);
                    recordPhase("bridge_temp_write", writeNs);
                    shared.accept(new CacheWriteEvent(
                        this.repoName,
                        assetPath,
                        tmp,
                        bytes.length,
                        Instant.now()
                    ));
                } catch (final Exception ex) {
                    EcsLogger.warn("com.auto1.pantera.adapters.npm")
                        .message("Failed to fire npm CacheWriteEvent")
                        .eventCategory("process")
                        .eventAction("cache_write_event")
                        .eventOutcome("failure")
                        .field("repository.name", this.repoName)
                        .field("url.path", assetPath)
                        .error(ex)
                        .log();
                } finally {
                    deleteQuietly(tmp);
                    recordPhase("bridge_total", entryNs);
                }
            })
            .exceptionally(err -> {
                EcsLogger.debug("com.auto1.pantera.adapters.npm")
                    .message("npm cache-write bridge: failed to read just-saved asset")
                    .field("repository.name", this.repoName)
                    .field("url.path", assetPath)
                    .error(err)
                    .log();
                return null;
            });
    }

    /**
     * Phase 10.5 profiler — emit per-phase histogram for the bridge stages
     * under the same {@code pantera_proxy_phase_seconds} histogram used by
     * the rest of the npm path, tagged by repo name.
     */
    private void recordPhase(final String phase, final long startNs) {
        if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
            com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                .recordProxyPhaseDuration(this.repoName, phase, System.nanoTime() - startNs);
        }
    }

    /**
     * Best-effort temp-file delete; silent on failure.
     */
    private static void deleteQuietly(final Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (final Exception ignored) {
            // best-effort
        }
    }
}
