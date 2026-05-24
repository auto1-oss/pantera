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
 * PyPI metadata request detector implementing cooldown SPI. Detects
 * whether an HTTP request path targets the PyPI Simple Index metadata
 * endpoint.
 *
 * <p>Two URL shapes are accepted:</p>
 * <ul>
 *   <li><b>Standard PEP 503</b>: {@code /simple/<package>/} — pip is
 *       configured with {@code --index-url <base>/simple} so it
 *       fetches {@code <base>/simple/<pkg>/}.</li>
 *   <li><b>JFrog Artifactory-compatible</b>: {@code /<package>/} —
 *       pip is configured with {@code --index-url <base>/api/pypi/<repo>}
 *       (no {@code /simple} suffix); pip then fetches
 *       {@code <base>/api/pypi/<repo>/<pkg>/}. After
 *       {@code ApiRoutingSlice} and the repo-prefix strip, the path
 *       arriving at this slice is bare {@code /<pkg>/}.</li>
 * </ul>
 *
 * <p>The artifact-coordinates check ({@link
 * com.auto1.pantera.pypi.http.ProxySlice#extract}) runs ahead of this
 * detector in the dispatcher, so multi-segment file paths like
 * {@code /packages/.../*.whl} and JSON-API paths like
 * {@code /pypi/<pkg>/json} are routed elsewhere before this matcher
 * sees them. The bare-{@code /<pkg>/} branch is therefore safe to
 * accept any single-segment path.</p>
 *
 * @since 2.2.0
 */
public final class PypiMetadataRequestDetector implements MetadataRequestDetector {

    /**
     * Standard PEP 503 form — explicit {@code /simple/<pkg>/?} with
     * optional leading repo prefix (e.g.
     * {@code /pypi_proxy/simple/requests/}). Trailing slash optional
     * to match pip's behaviour with and without it.
     */
    private static final Pattern SIMPLE_INDEX_PATTERN = Pattern.compile(
        "^(?:.*/)?simple/([^/]+)/?$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * JFrog Artifactory-compatible form — bare {@code /<pkg>/} (single
     * segment, trailing slash REQUIRED). pip's {@code --index-url
     * <base>/api/pypi/<repo>} (no {@code /simple} suffix) makes pip
     * fetch {@code <base>/api/pypi/<repo>/<pkg>/}, and after
     * {@link com.auto1.pantera.http.ApiRoutingSlice} + repo-prefix
     * strip the path arriving at the proxy slice is exactly
     * {@code /<pkg>/}. Requiring the trailing slash avoids matching
     * file paths like {@code /packages/foo.tar.gz} (no trailing
     * {@code /}) and JSON-API paths like {@code /pypi/foo/json}
     * (multi-segment).
     *
     * <p>The negative lookahead excludes the bare {@code /simple/}
     * literal — that's the empty index root, not a request for a
     * package named "simple". The SIMPLE_INDEX_PATTERN owns
     * {@code /simple/<pkg>/?} explicitly.</p>
     */
    private static final Pattern JFROG_INDEX_PATTERN = Pattern.compile(
        "^/(?!simple/?$)([^/]+)/$",
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
        return SIMPLE_INDEX_PATTERN.matcher(path).matches()
            || JFROG_INDEX_PATTERN.matcher(path).matches();
    }

    @Override
    public Optional<String> extractPackageName(final String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        final Matcher simpleMatcher = SIMPLE_INDEX_PATTERN.matcher(path);
        if (simpleMatcher.matches()) {
            return Optional.of(simpleMatcher.group(1));
        }
        final Matcher jfrogMatcher = JFROG_INDEX_PATTERN.matcher(path);
        if (jfrogMatcher.matches()) {
            return Optional.of(jfrogMatcher.group(1));
        }
        return Optional.empty();
    }

    @Override
    public String repoType() {
        return REPO_TYPE;
    }
}
