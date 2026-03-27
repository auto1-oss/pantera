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

import com.auto1.pantera.npm.proxy.model.NpmAsset;
import com.auto1.pantera.npm.proxy.model.NpmPackage;
import io.reactivex.Completable;
import io.reactivex.Maybe;

/**
 * NPM Proxy storage interface.
 * @since 0.1
 */
public interface NpmProxyStorage {
    /**
     * Persist NPM Package.
     * @param pkg Package to persist
     * @return Completion or error signal
     */
    Completable save(NpmPackage pkg);

    /**
     * Persist NPM Asset.
     * @param asset Asset to persist
     * @return Completion or error signal
     */
    Completable save(NpmAsset asset);

    /**
     * Retrieve NPM package by name.
     * @param name Package name
     * @return NPM package or empty
     */
    Maybe<NpmPackage> getPackage(String name);

    /**
     * Retrieve NPM asset by path.
     * @param path Asset path
     * @return NPM asset or empty
     */
    Maybe<NpmAsset> getAsset(String path);

    /**
     * Retrieve package metadata (without loading full content into memory).
     * Returns only the metadata (last-modified, refreshed dates).
     * @param name Package name
     * @return Package metadata or empty
     */
    Maybe<NpmPackage.Metadata> getPackageMetadata(String name);

    /**
     * Retrieve package content as reactive stream (without loading into memory).
     * @param name Package name
     * @return Package content as reactive Content or empty
     */
    Maybe<com.auto1.pantera.asto.Content> getPackageContent(String name);

    /**
     * Retrieve pre-computed abbreviated package content as reactive stream.
     * This is memory-efficient for npm install requests that only need abbreviated format.
     * Falls back to empty if abbreviated version is not cached.
     * @param name Package name
     * @return Abbreviated package content as reactive Content or empty
     */
    Maybe<com.auto1.pantera.asto.Content> getAbbreviatedContent(String name);

    /**
     * Check if abbreviated metadata exists for a package.
     * @param name Package name
     * @return True if abbreviated metadata is cached
     */
    Maybe<Boolean> hasAbbreviatedContent(String name);

    /**
     * Save only the metadata file (meta.meta) without overwriting content.
     * Used for updating refresh timestamps on conditional 304 responses.
     * @param name Package name
     * @param metadata Metadata to save
     * @return Completion or error signal
     */
    Completable saveMetadataOnly(String name, NpmPackage.Metadata metadata);
}
