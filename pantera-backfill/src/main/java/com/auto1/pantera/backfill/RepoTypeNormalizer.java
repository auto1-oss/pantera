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
package com.auto1.pantera.backfill;

/**
 * Normalises raw Pantera repo type strings to scanner type keys
 * understood by {@link ScannerFactory}.
 *
 * <p>Currently only strips the {@code -proxy} suffix
 * (e.g. {@code docker-proxy} → {@code docker}).
 * Other compound suffixes (e.g. {@code -hosted}, {@code -group}) are out of
 * scope and will surface as unknown types in {@link ScannerFactory}.</p>
 *
 * @since 1.20.13
 */
final class RepoTypeNormalizer {

    /**
     * Private ctor — utility class, not instantiable.
     */
    private RepoTypeNormalizer() {
    }

    /**
     * Normalize a raw repo type by stripping the {@code -proxy} suffix.
     *
     * @param rawType Raw {@code repo.type} value from the YAML config
     * @return Normalised scanner type string
     */
    static String normalize(final String rawType) {
        final String suffix = "-proxy";
        if (rawType.endsWith(suffix)) {
            return rawType.substring(0, rawType.length() - suffix.length());
        }
        return rawType;
    }
}
