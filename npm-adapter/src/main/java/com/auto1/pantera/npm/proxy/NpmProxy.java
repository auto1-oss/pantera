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
package com.auto1.pantera.npm.proxy;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.rx.RxStorageWrapper;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.context.ContextualExecutor;
import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import com.auto1.pantera.http.log.EcsLogger;
import io.reactivex.Maybe;
import io.reactivex.Scheduler;
import io.reactivex.schedulers.Schedulers;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * NPM Proxy.
 * @since 0.1
 */
public class NpmProxy {

    /**
     * Default metadata TTL (12 hours).
     * Metadata (package.json with version lists) should be refreshed periodically
     * to pick up new package versions from upstream.
     */
    public static final Duration DEFAULT_METADATA_TTL = Duration.ofHours(12);

    /**
     * The storage.
     */
    private final NpmProxyStorage storage;

    /**
     * Remote repository client.
     */
    private final NpmRemote remote;

    /**
     * Metadata TTL - how long before cached metadata is considered stale.
     */
    private final Duration metadataTtl;

    /**
     * Packages currently being refreshed in background (stale-while-revalidate).
     * Prevents duplicate refresh operations for the same package.
     */
    private final ConcurrentHashMap.KeySetView<String, Boolean> refreshing;

    /**
     * Contextualised RxJava scheduler for background refresh.
     * Propagates ThreadContext (ECS fields) and APM span automatically,
     * replacing the per-call MDC capture/restore pattern.
     */
    private final Scheduler backgroundScheduler;

    /**
     * Optional cache-write hook fired after a successful asset save (cache miss
     * → upstream fetch → persist to storage). Receives the asset path so the
     * consumer can materialise the freshly-saved bytes (typically into a temp
     * file) and fire a {@link com.auto1.pantera.http.cache.CacheWriteEvent}.
     *
     * <p>Default is a no-op so existing constructors and tests stay unchanged.
     * Production wiring (see {@code NpmProxyAdapter}) installs a consumer that
     * bridges to {@link com.auto1.pantera.http.cache.CacheWriteCallbackRegistry}
     * for prefetch dispatch — mirrors the path
     * {@code BaseCachedProxySlice} / {@code ProxyCacheWriter} use for the other
     * proxy adapters.
     *
     * <p>Hook is invoked synchronously after {@code storage.save} completes,
     * before the asset is reloaded for the response. Throws are swallowed by
     * the consumer per the {@code CacheWriteEvent} contract — never on the
     * serve path.</p>
     */
    private final Consumer<String> cacheWriteHook;

    /**
     * Phase 11.5 — fine-grained sub-phase recorder for the cold-cache asset
     * path (cache_check / upstream_fetch_and_save / save / reload). Receives
     * (phase name, durationNs). Default = no-op so existing constructors and
     * tests stay unchanged. Production wiring (see {@code NpmProxyAdapter})
     * installs a consumer that bridges to
     * {@link com.auto1.pantera.metrics.MicrometerMetrics#recordProxyPhaseDuration}.
     *
     * <p>Phase 11.5 instrumentation only — no behaviour change.
     */
    private final BiConsumer<String, Long> phaseRecorder;

    /**
     * Ctor.
     * @param remote Uri remote
     * @param storage Adapter storage
     * @param client Client slices
     */
    public NpmProxy(final URI remote, final Storage storage, final ClientSlices client) {
        this(
            new RxNpmProxyStorage(new RxStorageWrapper(storage)),
            new HttpNpmRemote(new UriClientSlice(client, remote)),
            DEFAULT_METADATA_TTL
        );
    }

    /**
     * Ctor.
     * @param storage Adapter storage
     * @param client Client slice
     */
    public NpmProxy(final Storage storage, final Slice client) {
        this(
            new RxNpmProxyStorage(new RxStorageWrapper(storage)),
            new HttpNpmRemote(client),
            DEFAULT_METADATA_TTL
        );
    }

    /**
     * Ctor with configurable metadata TTL.
     * @param storage Adapter storage
     * @param client Client slice
     * @param metadataTtl Metadata TTL duration
     */
    public NpmProxy(final Storage storage, final Slice client, final Duration metadataTtl) {
        this(
            new RxNpmProxyStorage(new RxStorageWrapper(storage)),
            new HttpNpmRemote(client),
            metadataTtl
        );
    }

    /**
     * Ctor with cache-write hook.
     *
     * <p>Used by production wiring to fire {@code CacheWriteEvent} after each
     * successful asset save so the speculative-prefetch dispatcher can warm
     * the cache for direct dependencies. The hook receives the asset path
     * (e.g. {@code @scope/pkg/-/pkg-1.2.3.tgz}) immediately after
     * {@code storage.save(asset)} completes and is responsible for
     * materialising the bytes + invoking the registry callback.</p>
     *
     * @param storage Adapter storage
     * @param client Client slice
     * @param metadataTtl Metadata TTL duration
     * @param cacheWriteHook Post-save hook invoked with the asset path on
     *                       cache-miss writes; null treated as no-op
     */
    public NpmProxy(
        final Storage storage,
        final Slice client,
        final Duration metadataTtl,
        final Consumer<String> cacheWriteHook
    ) {
        this(
            new RxNpmProxyStorage(new RxStorageWrapper(storage)),
            new HttpNpmRemote(client),
            metadataTtl,
            cacheWriteHook,
            null
        );
    }

    /**
     * Production ctor with phase recorder (Phase 11.5).
     *
     * <p>Wires fine-grained sub-phase timers for the cold-cache asset path
     * (cache_check / upstream_fetch_and_save / save / reload) into the
     * supplied {@code BiConsumer<phase, durationNs>}. Used by
     * {@code NpmProxyAdapter} to bridge into
     * {@link com.auto1.pantera.metrics.MicrometerMetrics#recordProxyPhaseDuration}
     * tagged by repository name.
     *
     * @param storage Adapter storage
     * @param client Client slice
     * @param metadataTtl Metadata TTL duration
     * @param cacheWriteHook Post-save hook on cache-miss writes; null treated as no-op
     * @param phaseRecorder Sub-phase timer recorder; null treated as no-op
     */
    public NpmProxy(
        final Storage storage,
        final Slice client,
        final Duration metadataTtl,
        final Consumer<String> cacheWriteHook,
        final BiConsumer<String, Long> phaseRecorder
    ) {
        this(
            new RxNpmProxyStorage(new RxStorageWrapper(storage), phaseRecorder),
            new HttpNpmRemote(client),
            metadataTtl,
            cacheWriteHook,
            phaseRecorder
        );
    }

    /**
     * Default-scoped ctor (for tests).
     * @param storage NPM storage
     * @param remote Remote repository client
     */
    NpmProxy(final NpmProxyStorage storage, final NpmRemote remote) {
        this(storage, remote, DEFAULT_METADATA_TTL, null, null);
    }

    /**
     * Default-scoped ctor with TTL (for tests).
     * @param storage NPM storage
     * @param remote Remote repository client
     * @param metadataTtl Metadata TTL duration
     */
    NpmProxy(final NpmProxyStorage storage, final NpmRemote remote, final Duration metadataTtl) {
        this(storage, remote, metadataTtl, null, null);
    }

    /**
     * Default-scoped ctor with TTL and cache-write hook (for tests + wiring).
     * @param storage NPM storage
     * @param remote Remote repository client
     * @param metadataTtl Metadata TTL duration
     * @param cacheWriteHook Post-save hook on cache-miss writes; null treated as no-op
     */
    NpmProxy(
        final NpmProxyStorage storage,
        final NpmRemote remote,
        final Duration metadataTtl,
        final Consumer<String> cacheWriteHook
    ) {
        this(storage, remote, metadataTtl, cacheWriteHook, null);
    }

    /**
     * Default-scoped ctor with TTL, cache-write hook, and phase recorder.
     * Phase 11.5 fine-grained sub-phase profiling.
     *
     * @param storage NPM storage
     * @param remote Remote repository client
     * @param metadataTtl Metadata TTL duration
     * @param cacheWriteHook Post-save hook on cache-miss writes; null treated as no-op
     * @param phaseRecorder (phase, durationNs) recorder; null treated as no-op
     */
    NpmProxy(
        final NpmProxyStorage storage,
        final NpmRemote remote,
        final Duration metadataTtl,
        final Consumer<String> cacheWriteHook,
        final BiConsumer<String, Long> phaseRecorder
    ) {
        this.storage = storage;
        this.remote = remote;
        this.metadataTtl = metadataTtl;
        this.refreshing = ConcurrentHashMap.newKeySet();
        // Wrap ForkJoinPool.commonPool with ContextualExecutor so background
        // refresh callbacks inherit the caller's ThreadContext (trace.id etc.)
        // and APM span. This replaces the per-call MDC capture/restore.
        final Executor ctxExec = ContextualExecutor.contextualize(ForkJoinPool.commonPool());
        this.backgroundScheduler = Schedulers.from(ctxExec);
        this.cacheWriteHook = cacheWriteHook == null ? path -> { } : cacheWriteHook;
        this.phaseRecorder = phaseRecorder == null ? (phase, ns) -> { } : phaseRecorder;
    }

    /**
     * Retrieve package metadata.
     * @param name Package name
     * @return Package metadata (cached or downloaded from remote repository)
     */
    public Maybe<NpmPackage> getPackage(final String name) {
        return this.storage.getPackage(name).flatMap(
            pkg -> this.remotePackage(name).switchIfEmpty(Maybe.just(pkg))
        ).switchIfEmpty(Maybe.defer(() -> this.remotePackage(name)));
    }

    /**
     * Retrieve package metadata only (without loading full content into memory).
     * This is memory-efficient for large packages.
     * Checks TTL and refreshes from remote if stale.
     * @param name Package name
     * @return Package metadata or empty
     */
    public Maybe<NpmPackage.Metadata> getPackageMetadataOnly(final String name) {
        return this.storage.getPackageMetadata(name)
            .flatMap(metadata -> {
                if (this.isStale(metadata.lastRefreshed())) {
                    // Stale-while-revalidate: serve stale immediately,
                    // trigger background refresh for next request
                    this.backgroundRefresh(name);
                }
                return Maybe.just(metadata);
            })
            .switchIfEmpty(Maybe.defer(() -> this.remotePackageMetadataAndSave(name)));
    }

    /**
     * Retrieve package content as reactive stream (without loading into memory).
     * This is memory-efficient for large packages.
     * Checks TTL and refreshes from remote if stale.
     * @param name Package name
     * @return Package content as reactive Content or empty
     */
    public Maybe<com.auto1.pantera.asto.Content> getPackageContentStream(final String name) {
        return this.storage.getPackageMetadata(name)
            .flatMap(metadata -> {
                if (this.isStale(metadata.lastRefreshed())) {
                    // Stale-while-revalidate: serve stale immediately,
                    // trigger background refresh for next request
                    this.backgroundRefresh(name);
                }
                return this.storage.getPackageContent(name);
            })
            .switchIfEmpty(Maybe.defer(() -> {
                // Not in storage - fetch from remote, save, then reload content stream
                return this.remotePackageAndSave(name).flatMap(
                    saved -> this.storage.getPackageContent(name)
                );
            }));
    }

    /**
     * Retrieve pre-computed abbreviated package content as reactive stream.
     * MEMORY OPTIMIZATION: This is the most efficient path for npm install requests.
     * Returns pre-computed abbreviated JSON without loading/parsing full metadata.
     * Checks TTL and refreshes from remote if stale.
     * @param name Package name
     * @return Abbreviated package content or empty (fall back to full if not available)
     */
    public Maybe<com.auto1.pantera.asto.Content> getAbbreviatedContentStream(final String name) {
        return this.storage.getPackageMetadata(name)
            .flatMap(metadata -> {
                if (this.isStale(metadata.lastRefreshed())) {
                    // Stale-while-revalidate: serve stale immediately,
                    // trigger background refresh for next request
                    this.backgroundRefresh(name);
                }
                return this.storage.getAbbreviatedContent(name);
            })
            .switchIfEmpty(Maybe.defer(() -> {
                // Not in storage - fetch from remote, save, then get abbreviated
                return this.remotePackageAndSave(name).flatMap(
                    saved -> this.storage.getAbbreviatedContent(name)
                );
            }));
    }

    /**
     * Check if abbreviated content is available for a package.
     * @param name Package name
     * @return True if abbreviated is cached
     */
    public Maybe<Boolean> hasAbbreviatedContent(final String name) {
        return this.storage.hasAbbreviatedContent(name);
    }

    /**
     * Check if cached metadata is stale based on TTL.
     * @param lastRefreshed When the metadata was last refreshed
     * @return True if metadata is stale and should be refreshed
     */
    private boolean isStale(final OffsetDateTime lastRefreshed) {
        final Duration age = Duration.between(lastRefreshed, OffsetDateTime.now());
        return age.compareTo(this.metadataTtl) > 0;
    }

    /**
     * Trigger background refresh of a package (stale-while-revalidate pattern).
     * Serves stale content immediately while refreshing in background.
     * Uses a ConcurrentHashMap.KeySetView to deduplicate in-flight refreshes.
     *
     * <p>Uses a {@link ContextualExecutor}-wrapped scheduler so that
     * background callbacks inherit the caller's ThreadContext (trace.id,
     * client.ip) and APM span automatically — no per-call MDC capture needed.
     *
     * @param name Package name
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void backgroundRefresh(final String name) {
        if (this.refreshing.add(name)) {
            // Try conditional request first if we have a stored upstream ETag.
            // The backgroundScheduler propagates ThreadContext automatically.
            this.conditionalRefresh(name)
                .subscribeOn(this.backgroundScheduler)
                .doFinally(() -> this.refreshing.remove(name))
                .subscribe(
                    saved ->
                        EcsLogger.debug("com.auto1.pantera.npm.proxy")
                            .message("Background refresh completed")
                            .eventCategory("database")
                            .eventAction("stale_while_revalidate")
                            .eventOutcome("success")
                            .field("package.name", name)
                            .log(),
                    err ->
                        EcsLogger.warn("com.auto1.pantera.npm.proxy")
                            .message("Background refresh failed")
                            .eventCategory("database")
                            .eventAction("stale_while_revalidate")
                            .eventOutcome("failure")
                            .field("package.name", name)
                            .error(err)
                            .log(),
                    () -> this.refreshing.remove(name)
                );
        }
    }

    /**
     * Attempt conditional refresh using stored upstream ETag.
     * If upstream returns 304 (not modified), just update the refresh timestamp.
     * Otherwise, do a full refresh.
     * @param name Package name
     * @return Completion signal
     */
    private Maybe<Boolean> conditionalRefresh(final String name) {
        return this.storage.getPackageMetadata(name)
            .flatMap(metadata -> {
                if (metadata.upstreamEtag().isPresent()
                    && this.remote instanceof HttpNpmRemote) {
                    // Try conditional request with If-None-Match
                    return ((HttpNpmRemote) this.remote)
                        .loadPackageConditional(name, metadata.upstreamEtag().get())
                        .flatMap(pkg -> this.storage.save(pkg).andThen(Maybe.just(Boolean.TRUE)))
                        .switchIfEmpty(Maybe.defer(() -> {
                            // 304 Not Modified — just update refresh timestamp
                            final NpmPackage.Metadata updated = new NpmPackage.Metadata(
                                metadata.lastModified(),
                                OffsetDateTime.now(),
                                metadata.contentHash().orElse(null),
                                metadata.abbreviatedHash().orElse(null),
                                metadata.upstreamEtag().orElse(null)
                            );
                            return this.storage.saveMetadataOnly(name, updated)
                                .andThen(Maybe.just(Boolean.TRUE));
                        }));
                }
                // No stored ETag or not HttpNpmRemote — do full refresh
                return this.remotePackageAndSave(name)
                    .defaultIfEmpty(Boolean.FALSE);
            })
            .switchIfEmpty(Maybe.defer(() -> this.remotePackageAndSave(name)));
    }

    /**
     * Retrieve asset.
     *
     * <p>On cache miss the asset is fetched from the upstream remote, persisted
     * to storage, and reloaded for the response. Immediately after the save
     * completes (and before the reload) the configured
     * {@link #cacheWriteHook} is fired with the asset path so production
     * wiring can dispatch a {@link com.auto1.pantera.http.cache.CacheWriteEvent}
     * for speculative pre-fetch — symmetric with how
     * {@code BaseCachedProxySlice} fires its own callback after a successful
     * proxy cache write. The hook NEVER blocks the serve path: any throwable
     * is swallowed by the consumer per the registry contract.</p>
     *
     * @param path Asset path
     * @return Asset data (cached or downloaded from remote repository)
     */
    public Maybe<NpmAsset> getAsset(final String path) {
        // Phase 11.5: time each sub-phase of the cold-cache asset path so the
        // 54ms/req asset_total observed in Phase 10.5 can be decomposed.
        // Timers use Maybe.defer / Single.defer so duration reflects actual
        // subscription time (per-request), not assembly time.
        final long checkStartNs = System.nanoTime();
        return this.storage.getAsset(path)
            .doOnEvent((asset, err) -> recordPhase(
                "npm_storage_cache_check", checkStartNs
            ))
            .switchIfEmpty(
                Maybe.defer(() -> {
                    // Cache miss path. Time upstream HTTP fetch (header phase),
                    // then save (which fuses upstream-body-stream with disk
                    // write since the asset wraps the upstream Publisher),
                    // then reload (storage.getAsset → readAsset).
                    final long fetchStartNs = System.nanoTime();
                    return this.remote.loadAsset(path, null)
                        .doOnEvent((asset, err) -> recordPhase(
                            "npm_upstream_fetch_open", fetchStartNs
                        ))
                        .flatMap(asset -> {
                            final long saveStartNs = System.nanoTime();
                            return this.storage.save(asset)
                                .doOnComplete(() -> recordPhase(
                                    "npm_storage_save", saveStartNs
                                ))
                                .doOnComplete(() -> this.fireCacheWriteHook(path))
                                .andThen(Maybe.defer(() -> {
                                    final long reloadStartNs = System.nanoTime();
                                    return this.storage.getAsset(path)
                                        .doOnEvent((a, err) -> recordPhase(
                                            "npm_storage_reload", reloadStartNs
                                        ));
                                }));
                        });
                })
            );
    }

    /**
     * Phase 11.5 — emit sub-phase duration via the configured recorder.
     * No-op when recorder is the default (null in ctor).
     *
     * @param phase phase name (e.g. {@code "npm_storage_save"})
     * @param startNs nanoTime captured at phase entry
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordPhase(final String phase, final long startNs) {
        try {
            this.phaseRecorder.accept(phase, System.nanoTime() - startNs);
        } catch (final Exception thrown) {
            // Recorder must never break the serve path.
            EcsLogger.debug("com.auto1.pantera.npm.proxy")
                .message("npm phaseRecorder threw; serve path unaffected")
                .field("phase", phase)
                .error(thrown)
                .log();
        }
    }

    /**
     * Fire the cache-write hook with the freshly-saved asset path. Throws are
     * swallowed: a broken consumer must never break the serve path.
     *
     * @param path Asset path that was just saved
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void fireCacheWriteHook(final String path) {
        try {
            this.cacheWriteHook.accept(path);
        } catch (final Exception thrown) {
            EcsLogger.warn("com.auto1.pantera.npm.proxy")
                .message("npm cacheWriteHook threw; serve path unaffected")
                .eventCategory("process")
                .eventAction("cache_write_hook")
                .eventOutcome("failure")
                .field("url.path", path)
                .error(thrown)
                .log();
        }
    }

    /**
     * CompletionStage-based boundary adapter for {@link #getAsset(String)}.
     * Converts the internal RxJava {@code Maybe<NpmAsset>} to
     * {@code CompletableFuture<Optional<NpmAsset>>} so callers on hot paths
     * (e.g. {@code DownloadAssetSlice}) can stay in the CompletionStage world
     * without importing RxJava types.
     *
     * @param path Asset path
     * @return Future containing the asset, or empty if not found
     */
    public java.util.concurrent.CompletableFuture<java.util.Optional<NpmAsset>> getAssetAsync(
        final String path
    ) {
        return this.getAsset(path)
            .map(java.util.Optional::of)
            .toSingle(java.util.Optional.empty())
            .to(hu.akarnokd.rxjava2.interop.SingleInterop.get())
            .toCompletableFuture();
    }

    /**
     * Close NPM Proxy adapter and underlying remote client.
     * @throws IOException when underlying remote client fails to close
     */
    public void close() throws IOException {
        this.remote.close();
    }

    /**
     * Access underlying remote client.
     * @return Remote client
     */
    public NpmRemote remoteClient() {
        return this.remote;
    }

    /**
     * Get package from remote repository and save it to storage.
     * @param name Package name
     * @return Npm Package
     */
    private Maybe<NpmPackage> remotePackage(final String name) {
        final Maybe<NpmPackage> res;
        final Maybe<NpmPackage> pckg = this.remote.loadPackage(name);
        if (pckg == null) {
            res = Maybe.empty();
        } else {
            res = pckg.flatMap(
                pkg -> this.storage.save(pkg).andThen(Maybe.just(pkg))
            );
        }
        return res;
    }

    /**
     * Get package from remote repository, save it to storage, and return a
     * completion signal.
     *
     * <p>Does not return the {@link NpmPackage} instance itself to avoid
     * keeping large package contents in memory; callers should reload from
     * storage using a streaming API.</p>
     *
     * @param name Package name
     * @return Completion signal (true if saved, empty if not found)
     */
    private Maybe<Boolean> remotePackageAndSave(final String name) {
        final Maybe<NpmPackage> pckg = this.remote.loadPackage(name);
        if (pckg == null) {
            return Maybe.empty();
        }
        return pckg.flatMap(
            pkg -> this.storage.save(pkg).andThen(Maybe.just(Boolean.TRUE))
        );
    }

    /**
     * Get package from remote repository, save it to storage, and return
     * metadata only.
     *
     * <p>Used by {@link #getPackageMetadataOnly(String)} to avoid an extra
     * metadata read from storage after a cache miss while still persisting the
     * full package state.</p>
     *
     * @param name Package name
     * @return Package metadata or empty if not found
     */
    private Maybe<NpmPackage.Metadata> remotePackageMetadataAndSave(final String name) {
        final Maybe<NpmPackage> pckg = this.remote.loadPackage(name);
        if (pckg == null) {
            return Maybe.empty();
        }
        return pckg.flatMap(
            pkg -> this.storage.save(pkg).andThen(Maybe.just(pkg.meta()))
        );
    }
}
