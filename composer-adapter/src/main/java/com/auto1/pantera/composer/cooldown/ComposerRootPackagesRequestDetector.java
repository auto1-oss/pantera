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
package com.auto1.pantera.composer.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;

import java.util.Optional;

/**
 * Detector for Composer root aggregation endpoints — {@code /packages.json}
 * and {@code /repo.json} — the repository root metadata described by the
 * Composer v1/v2 repository specification.
 *
 * <p>Unlike {@link ComposerMetadataRequestDetector} which targets
 * per-package metadata ({@code /p2/<vendor>/<pkg>.json} or
 * {@code /packages/<vendor>/<pkg>.json}), this detector matches the
 * root of the repository's URL space where the repository advertises
 * the {@code metadata-url} / {@code providers-url} scheme and, in
 * inline-packages configurations, may enumerate every published
 * version of every package.</p>
 *
 * <p>Root matches are exact — {@code /packages.json} or
 * {@code /repo.json} only. Per-package endpoints ending in
 * {@code .json} are explicitly rejected so the existing
 * {@link ComposerPackageMetadataHandler} continues to service
 * {@code /p2/...} and {@code /packages/...} requests unchanged.</p>
 *
 * <p>{@link #extractPackageName(String)} always returns empty — root
 * metadata is inherently multi-package and no per-package cooldown
 * lookup key exists. Consumers that call {@link #isMetadataRequest}
 * must know to dispatch to {@link ComposerRootPackagesFilter} / the
 * root-packages handler rather than treating the result as a
 * single-package metadata fetch.</p>
 *
 * @since 2.2.0
 */
public final class ComposerRootPackagesRequestDetector implements MetadataRequestDetector {

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "composer";

    @Override
    public boolean isMetadataRequest(final String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return "/packages.json".equals(path) || "/repo.json".equals(path);
    }

    /**
     * Root packages endpoints are multi-package aggregations; no
     * single package name can be extracted. Always returns empty.
     *
     * @param path Request path
     * @return Always empty
     */
    @Override
    public Optional<String> extractPackageName(final String path) {
        return Optional.empty();
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
