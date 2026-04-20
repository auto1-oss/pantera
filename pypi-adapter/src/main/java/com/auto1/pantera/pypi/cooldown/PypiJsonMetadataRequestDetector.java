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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detector for the PyPI JSON API endpoint {@code /pypi/<name>/json}.
 *
 * <p>PyPI serves two parallel metadata surfaces:</p>
 * <ul>
 *   <li><b>Simple Index</b> — {@code /simple/<name>/} (HTML / PEP 691 JSON).
 *       Handled by {@link PypiMetadataRequestDetector}.</li>
 *   <li><b>JSON API</b> — {@code /pypi/<name>/json} returns a JSON blob
 *       with {@code info.version} (latest), {@code releases} (all
 *       versions) and {@code urls} (files for info.version).
 *       pip uses this in some code paths; poetry / pip-tools use it
 *       heavily. This class matches only the <em>package-level</em>
 *       JSON endpoint.</li>
 * </ul>
 *
 * <p>We intentionally do NOT match the version-specific form
 * {@code /pypi/<name>/<version>/json} — those describe a single version
 * and are adequately covered by the artifact-layer cooldown check.
 * Filtering them separately would be redundant.</p>
 *
 * <p>Package name normalisation follows PEP 503: lowercase, collapse
 * runs of {@code [-_.]} to a single {@code -}. We expose the
 * <em>raw</em> name from the path so callers can match cooldown-stored
 * artifact keys verbatim; normalisation happens at the cooldown lookup
 * layer.</p>
 *
 * @since 2.2.0
 */
public final class PypiJsonMetadataRequestDetector {

    /**
     * Repository type identifier (shared with Simple-index detector).
     */
    private static final String REPO_TYPE = "pypi";

    /**
     * Matches {@code /pypi/<name>/json} with an optional path prefix
     * (e.g. {@code /pypi-proxy/pypi/requests/json}). Explicitly rejects:
     * <ul>
     *   <li>trailing path segments after {@code /json}</li>
     *   <li>version-specific endpoints {@code /pypi/<name>/<ver>/json}</li>
     *   <li>{@code /pypi/json} with no package name</li>
     * </ul>
     * Group 1 = package name.
     */
    private static final Pattern JSON_API_PATTERN = Pattern.compile(
        "^(?:.*/)?pypi/([^/]+)/json/?$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Match a path that ends with {@code /<version>/json}. We use this
     * to explicitly reject the version-specific JSON endpoint so it
     * never slips into the package-level flow. This is not part of
     * {@link #JSON_API_PATTERN} because the outer anchor there already
     * disallows extra segments, but keeping a second guard makes the
     * intent explicit for future readers.
     */
    private static final Pattern VERSION_JSON_PATTERN = Pattern.compile(
        "^(?:.*/)?pypi/[^/]+/[^/]+/json/?$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Whether the given request path targets the package-level JSON API.
     *
     * @param path Request path
     * @return true for {@code /pypi/<name>/json}, false otherwise
     */
    public boolean isMetadataRequest(final String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (VERSION_JSON_PATTERN.matcher(path).matches()
            && !JSON_API_PATTERN.matcher(path).matches()) {
            return false;
        }
        return JSON_API_PATTERN.matcher(path).matches();
    }

    /**
     * Extract the package name from a {@code /pypi/<name>/json} path.
     *
     * @param path Request path
     * @return Package name if parseable, else empty
     */
    public Optional<String> extractPackageName(final String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        final Matcher matcher = JSON_API_PATTERN.matcher(path);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        // The outer JSON_API_PATTERN already disallows a trailing
        // version segment because the package-name group forbids '/'.
        return Optional.of(matcher.group(1));
    }

    /**
     * Repository type this detector serves.
     *
     * @return {@code "pypi"}
     */
    public String repoType() {
        return REPO_TYPE;
    }
}
