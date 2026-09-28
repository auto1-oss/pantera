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

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHP Composer metadata request detector implementing cooldown SPI.
 * Detects Composer metadata endpoints and extracts package names.
 *
 * <p>Composer metadata endpoints:</p>
 * <ul>
 *   <li>{@code /packages/{vendor}/{package}.json} — package metadata</li>
 *   <li>{@code /p2/{vendor}/{package}.json} — Composer v2 lazy-provider endpoint</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class ComposerMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * Pattern matching {@code /packages/{vendor}/{package}.json}.
     */
    private static final Pattern PACKAGES_PATTERN =
        Pattern.compile("^/packages/([^/]+/[^/]+)\\.json$");

    /**
     * Pattern matching {@code /p2/{vendor}/{package}.json}.
     */
    private static final Pattern P2_PATTERN =
        Pattern.compile("^/p2/([^/]+/[^/]+)\\.json$");

    /**
     * Suffix of the Composer v2 dev-branch metadata file
     * ({@code /p2/<vendor>/<pkg>~dev.json}).
     */
    private static final String DEV_SUFFIX = "~dev";

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "composer";

    @Override
    public boolean isMetadataRequest(final String path) {
        return PACKAGES_PATTERN.matcher(path).matches()
            || P2_PATTERN.matcher(path).matches();
    }

    /**
     * Extract the package name used as the cooldown artifact key.
     *
     * <p>The name is the one dist downloads are keyed under
     * ({@code ProxyDownloadSlice}): lowercase {@code vendor/package}, with
     * the {@code ~dev} suffix of the dev-branch file stripped. Composer
     * package names are case-insensitive and the dev file lists versions
     * of the same package, so a dev version must map to the same block row
     * from either path, or unblocking one leaves the other in place. The
     * request path itself is untouched: callers still fetch the file named
     * in the request.</p>
     *
     * @param path Request path
     * @return Package name, or empty for a non-metadata path
     */
    @Override
    public Optional<String> extractPackageName(final String path) {
        Matcher matcher = PACKAGES_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Optional.of(cooldownKey(matcher.group(1)));
        }
        matcher = P2_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Optional.of(cooldownKey(matcher.group(1)));
        }
        return Optional.empty();
    }

    /**
     * Normalise a {@code vendor/package} name from a metadata path.
     *
     * @param name Name as it appears in the path
     * @return Lowercase name without the {@code ~dev} suffix
     */
    private static String cooldownKey(final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(DEV_SUFFIX)) {
            return lower.substring(0, lower.length() - DEV_SUFFIX.length());
        }
        return lower;
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
