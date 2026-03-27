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
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.rq.RequestLine;
import org.apache.http.client.utils.URIBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice decorator which redirects Composer API requests to repository format paths.
 * Supports both /api/composer/{repo} and /composer/{repo} patterns.
 */
public final class ComposerRoutingSlice implements Slice {

    /**
     * Composer API path pattern - matches /api/composer/{repo}/... or /composer/{repo}/...
     * Also handles prefixes like /test_prefix/api/composer/{repo}/...
     */
    private static final Pattern PTN_API_COMPOSER = Pattern.compile(
        "^(/[^/]+)?/(?:api/)?composer/([^/]+)(/.*)?$"
    );

    /**
     * Origin slice.
     */
    private final Slice origin;

    /**
     * Decorates slice with Composer API routing.
     * @param origin Origin slice
     */
    public ComposerRoutingSlice(final Slice origin) {
        this.origin = origin;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers headers, Content body
    ) {
        final String path = line.uri().getPath();
        final Matcher matcher = PTN_API_COMPOSER.matcher(path);
        
        if (matcher.matches()) {
            final String prefix = matcher.group(1);  // e.g., "/test_prefix" or null
            final String repo = matcher.group(2);     // e.g., "php_group"
            final String rest = matcher.group(3);     // e.g., "/packages.json" or null
            final String newPath = (prefix != null ? prefix : "") + "/" + repo + (rest != null ? rest : "");
            
            return this.origin.response(
                new RequestLine(
                    line.method().toString(),
                    new URIBuilder(line.uri()).setPath(newPath).toString(),
                    line.version()
                ),
                headers,
                body
            );
        }
        
        return this.origin.response(line, headers, body);
    }
}
