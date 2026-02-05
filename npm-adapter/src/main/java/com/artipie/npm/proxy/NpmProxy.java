/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy;

import com.artipie.asto.Storage;
import com.artipie.asto.rx.RxStorageWrapper;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.npm.proxy.model.NpmAsset;
import com.artipie.npm.proxy.model.NpmPackage;
import io.reactivex.Maybe;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;

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
     * Default-scoped ctor (for tests).
     * @param storage NPM storage
     * @param remote Remote repository client
     */
    NpmProxy(final NpmProxyStorage storage, final NpmRemote remote) {
        this(storage, remote, DEFAULT_METADATA_TTL);
    }

    /**
     * Default-scoped ctor with TTL (for tests).
     * @param storage NPM storage
     * @param remote Remote repository client
     * @param metadataTtl Metadata TTL duration
     */
    NpmProxy(final NpmProxyStorage storage, final NpmRemote remote, final Duration metadataTtl) {
        this.storage = storage;
        this.remote = remote;
        this.metadataTtl = metadataTtl;
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
                    // TTL expired - try to refresh from upstream
                    // If refresh fails, fall back to stale cached metadata
                    return this.remotePackageMetadataAndSave(name)
                        .switchIfEmpty(Maybe.just(metadata));
                }
                // Still fresh - return cached immediately
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
    public Maybe<com.artipie.asto.Content> getPackageContentStream(final String name) {
        return this.storage.getPackageMetadata(name)
            .flatMap(metadata -> {
                if (this.isStale(metadata.lastRefreshed())) {
                    // TTL expired - try to refresh from upstream
                    return this.remotePackageAndSave(name)
                        .flatMap(saved -> this.storage.getPackageContent(name))
                        .switchIfEmpty(this.storage.getPackageContent(name));
                }
                // Still fresh - return cached content
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
    public Maybe<com.artipie.asto.Content> getAbbreviatedContentStream(final String name) {
        return this.storage.getPackageMetadata(name)
            .flatMap(metadata -> {
                if (this.isStale(metadata.lastRefreshed())) {
                    // TTL expired - try to refresh from upstream
                    return this.remotePackageAndSave(name)
                        .flatMap(saved -> this.storage.getAbbreviatedContent(name))
                        .switchIfEmpty(this.storage.getAbbreviatedContent(name));
                }
                // Still fresh - return cached abbreviated content
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
     * Retrieve asset.
     * @param path Asset path
     * @return Asset data (cached or downloaded from remote repository)
     */
    public Maybe<NpmAsset> getAsset(final String path) {
        return this.storage.getAsset(path).switchIfEmpty(
            Maybe.defer(
                () -> this.remote.loadAsset(path, null).flatMap(
                    asset -> this.storage.save(asset)
                        .andThen(Maybe.just(asset))
                )
            )
        );
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
