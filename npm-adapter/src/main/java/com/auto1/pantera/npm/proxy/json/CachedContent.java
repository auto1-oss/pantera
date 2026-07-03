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
package com.auto1.pantera.npm.proxy.json;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cached package content representation.
 *
 * <p>PERFORMANCE OPTIMIZATION: Pre-compiles the regex pattern in constructor
 * instead of on every transformRef call. For packages with many versions,
 * this prevents thousands of redundant pattern compilations.</p>
 *
 * @since 0.1
 */
public final class CachedContent extends TransformedContent {
    /**
     * Regexp pattern template for asset links.
     */
    private static final String REF_PATTERN = "^(.+)/(%s/-/.+)$";

    /**
     * Pre-compiled pattern for this package.
     * Compiled once in constructor, reused for all transformRef calls.
     */
    private final Pattern compiledPattern;

    /**
     * Ctor.
     * @param content Package content to be transformed
     * @param pkg Package name
     */
    public CachedContent(final String content, final String pkg) {
        super(content);
        // Pre-compile pattern once instead of on every transformRef call
        this.compiledPattern = Pattern.compile(
            String.format(CachedContent.REF_PATTERN, Pattern.quote(pkg))
        );
    }

    @Override
    String transformRef(final String ref) {
        final Matcher matcher = this.compiledPattern.matcher(ref);
        if (matcher.matches()) {
            return String.format("/%s", matcher.group(2));
        }
        return ref;
    }
}
