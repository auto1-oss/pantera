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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Key;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Release version of a stored PyPI distribution.
 *
 * <p>Hosted files live at {@code <name>/<version>/<file>}, so the version
 * directory is authoritative. Files stored flat directly under the package
 * directory (legacy layouts) fall back to the version encoded in the
 * filename: {@code name-ver-...whl} / {@code name-ver-...egg} or
 * {@code name-ver.tar.gz} (and the other sdist archive suffixes).</p>
 *
 * @since 2.2.9
 */
final class DistFilename {

    /**
     * Source-distribution archive suffixes, longest first.
     */
    private static final List<String> SDIST_SUFFIXES = List.of(
        ".tar.gz", ".tar.bz2", ".tar.z", ".tgz", ".zip", ".tar"
    );

    /**
     * Filename.
     */
    private final String name;

    /**
     * Ctor.
     * @param name Distribution filename
     */
    DistFilename(final String name) {
        this.name = name;
    }

    /**
     * Version of the distribution stored at {@code key}: the version
     * directory for {@code <name>/<version>/<file>}, else the filename.
     * @param key Storage key relative to the repository root
     * @return Version, empty when it cannot be determined
     */
    static Optional<String> versionOf(final Key key) {
        final String[] parts = key.string().split("/");
        final Optional<String> result;
        if (parts.length >= 3) {
            result = Optional.of(parts[parts.length - 2]);
        } else {
            result = new DistFilename(parts[parts.length - 1]).version();
        }
        return result;
    }

    /**
     * Version encoded in the filename.
     * @return Version, empty when the name has no recognised shape
     */
    Optional<String> version() {
        final String lower = this.name.toLowerCase(Locale.ROOT);
        final Optional<String> result;
        if (lower.endsWith(".whl") || lower.endsWith(".egg")) {
            final String[] parts = this.name.substring(0, this.name.length() - 4).split("-");
            result = parts.length >= 2 ? Optional.of(parts[1]) : Optional.empty();
        } else {
            final String stem = this.stem(lower);
            final int dash = stem.lastIndexOf('-');
            result = dash > 0 && dash < stem.length() - 1
                ? Optional.of(stem.substring(dash + 1)) : Optional.empty();
        }
        return result;
    }

    /**
     * Filename without its source-distribution archive suffix.
     * @param lower Lower-cased filename
     * @return Stem
     */
    private String stem(final String lower) {
        String stem = this.name;
        for (final String suffix : DistFilename.SDIST_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                stem = this.name.substring(0, this.name.length() - suffix.length());
                break;
            }
        }
        return stem;
    }
}
