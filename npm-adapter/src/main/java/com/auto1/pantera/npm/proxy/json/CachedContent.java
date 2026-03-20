/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.json;

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
     * Package name.
     */
    private final String pkg;

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
        this.pkg = pkg;
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
