/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.npm.misc;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Memory-efficient URL transformer for NPM metadata.
 * 
 * <p>Transforms tarball URLs by prepending a prefix to relative URLs.
 * Uses regex-based string replacement which is simpler and more reliable
 * than byte-level manipulation.</p>
 * 
 * <p>Cached NPM metadata contains relative URLs like:
 * {@code "tarball":"/package/-/package-1.0.0.tgz"} or
 * {@code "tarball": "/package/-/package-1.0.0.tgz"} (with space)
 * This transformer prepends the host prefix to produce:
 * {@code "tarball":"http://host/npm/package/-/package-1.0.0.tgz"}</p>
 *
 * @since 1.20
 */
public final class ByteLevelUrlTransformer {

    /**
     * Pattern to match tarball URLs with relative paths.
     * Matches: "tarball": "/..." or "tarball":"/..."
     * Captures the relative path starting with /
     */
    private static final Pattern TARBALL_PATTERN = Pattern.compile(
        "(\"tarball\"\\s*:\\s*\")(/)([^\"]+\")"
    );

    /**
     * Transform NPM metadata by prepending prefix to relative tarball URLs.
     *
     * @param input Input JSON bytes (cached content with relative URLs)
     * @param prefix URL prefix to prepend (e.g., "http://localhost:8080/npm")
     * @return Transformed JSON bytes
     */
    public byte[] transform(final byte[] input, final String prefix) {
        final String content = new String(input, StandardCharsets.UTF_8);
        final Matcher matcher = TARBALL_PATTERN.matcher(content);
        
        if (!matcher.find()) {
            // No relative URLs to transform - return input as-is
            return input;
        }
        
        // Reset matcher and perform replacement
        matcher.reset();
        final StringBuffer result = new StringBuffer(content.length() + prefix.length() * 10);
        while (matcher.find()) {
            // Replace: "tarball": "/" + path" -> "tarball": "prefix/" + path"
            matcher.appendReplacement(result, "$1" + Matcher.quoteReplacement(prefix) + "$2$3");
        }
        matcher.appendTail(result);
        
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
}
