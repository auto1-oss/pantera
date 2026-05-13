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
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "composer";

    @Override
    public boolean isMetadataRequest(final String path) {
        return PACKAGES_PATTERN.matcher(path).matches()
            || P2_PATTERN.matcher(path).matches();
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        Matcher matcher = PACKAGES_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        matcher = P2_PATTERN.matcher(path);
        if (matcher.matches()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
