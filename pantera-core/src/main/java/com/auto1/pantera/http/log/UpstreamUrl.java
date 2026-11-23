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
package com.auto1.pantera.http.log;

/**
 * URL of an artifact on an upstream, for log records, audit records and
 * cache-writer context: the configured remote URL joined with the request
 * path by exactly one slash.
 *
 * <p>A remote configured with a trailing slash
 * ({@code https://repository.jboss.org/}) concatenated with a request path
 * gave {@code https://repository.jboss.org//software/...} in
 * {@code url.full}. The request itself was never sent that way (the HTTP
 * client joins prefix and path on its own), so the record showed a URL that
 * was not the one asked.</p>
 *
 * @since 2.2.14
 */
public final class UpstreamUrl {

    /**
     * Configured remote URL.
     */
    private final String base;

    /**
     * Ctor.
     * @param base Configured remote URL, with or without a trailing slash
     */
    public UpstreamUrl(final String base) {
        this.base = base;
    }

    /**
     * URL of the given path on the upstream.
     * @param path Request path, with or without a leading slash
     * @return Joined URL
     */
    public String of(final String path) {
        int end = this.base.length();
        while (end > 0 && this.base.charAt(end - 1) == '/') {
            end -= 1;
        }
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start += 1;
        }
        return new StringBuilder(end + path.length() + 1)
            .append(this.base, 0, end)
            .append('/')
            .append(path, start, path.length())
            .toString();
    }
}
