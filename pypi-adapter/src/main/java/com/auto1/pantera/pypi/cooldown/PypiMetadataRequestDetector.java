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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRequestDetector;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PyPI metadata request detector implementing cooldown SPI.
 * Detects whether an HTTP request path targets the PyPI Simple Index metadata endpoint.
 *
 * <p>Metadata requests match the pattern {@code /simple/{package}/} where the package
 * name follows PEP 503 normalization rules.</p>
 *
 * <p>Non-metadata requests (artifact downloads) include paths like
 * {@code /packages/.../*.tar.gz} or {@code /packages/.../*.whl}.</p>
 *
 * @since 2.2.0
 */
public final class PypiMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * Pattern matching {@code /simple/{package}/} with optional trailing slash.
     * The package name is captured in group 1.
     * Allows for an optional leading path prefix before {@code /simple/} to handle
     * repository-scoped paths like {@code /pypi-proxy/simple/requests/}.
     */
    private static final Pattern SIMPLE_INDEX_PATTERN = Pattern.compile(
        "^(?:.*/)?simple/([^/]+)/?$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Repository type identifier.
     */
    private static final String REPO_TYPE = "pypi";

    @Override
    public boolean isMetadataRequest(final String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return SIMPLE_INDEX_PATTERN.matcher(path).matches();
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        final Matcher matcher = SIMPLE_INDEX_PATTERN.matcher(path);
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
