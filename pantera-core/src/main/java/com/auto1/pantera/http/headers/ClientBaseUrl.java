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
package com.auto1.pantera.http.headers;

import com.auto1.pantera.http.Headers;

import java.util.List;
import java.util.Optional;

/**
 * Client-facing base URL of the repository a request actually addressed.
 *
 * <p>Stamped once by {@code SliceByPath} as {@link #HEADER} and read by every
 * slice that writes an absolute URL into a response body (npm tarball links).
 * Stamping happens above the group resolver, so a group member sees the
 * <em>group's</em> base rather than its own — which is the whole point: a
 * client that configured the group as its registry must receive URLs under
 * the group, or strict clients (corepack) reject them.</p>
 */
public final class ClientBaseUrl {

    /**
     * Internal header carrying the addressed repository's client-facing base URL.
     */
    public static final String HEADER = "X-Pantera-Client-Base";

    /**
     * Header stamped by {@code ApiRoutingSlice} with the pre-rewrite client
     * path; preferred over the live request path because it still carries the
     * {@code /api/<type>} segment the client configured as its registry.
     */
    public static final String ORIGINAL_PATH = "X-Original-Path";

    /**
     * Request headers.
     */
    private final Headers headers;

    /**
     * Ctor.
     * @param headers Request headers
     */
    public ClientBaseUrl(final Headers headers) {
        this.headers = headers;
    }

    /**
     * Scheme and authority the client used, honouring reverse-proxy
     * forwarding headers.
     * @return e.g. {@code https://reg.example.com}
     */
    public String origin() {
        return String.format(
            "%s://%s",
            this.first("X-Forwarded-Proto").orElse("http"),
            this.first("X-Forwarded-Host").or(() -> this.first("Host")).orElse("localhost")
        );
    }

    /**
     * The already-stamped base, if an outer slice set one.
     * @return Stamped base URL
     */
    public Optional<String> stamped() {
        return this.first(ClientBaseUrl.HEADER);
    }

    /**
     * Build the absolute repository base by removing the slice-relative
     * remainder from the client-facing path.
     *
     * <p>Returns empty when {@code remainder} is not a suffix of
     * {@code originalClientPath} — deliberately preferring "no value" over a
     * wrong URL, so consumers fall through to their existing fallback chain.</p>
     *
     * @param originalClientPath Path as the client sent it
     * @param remainder Path relative to the repository, e.g. {@code /pnpm}
     * @return Absolute base URL, or empty if it cannot be derived safely
     */
    public Optional<String> derive(final String originalClientPath, final String remainder) {
        final Optional<String> result;
        if (originalClientPath == null || originalClientPath.isEmpty()) {
            result = Optional.empty();
        } else if (remainder == null || remainder.isEmpty() || "/".equals(remainder)) {
            result = Optional.of(this.absolute(originalClientPath));
        } else if (originalClientPath.endsWith(remainder)) {
            result = Optional.of(
                this.absolute(
                    originalClientPath.substring(
                        0, originalClientPath.length() - remainder.length()
                    )
                )
            );
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Prepend origin and any forwarded prefix to a repository path.
     * @param repoPath Repository base path
     * @return Absolute URL without a trailing slash
     */
    private String absolute(final String repoPath) {
        return this.origin() + this.forwardedPrefix() + ClientBaseUrl.withoutTrailingSlash(repoPath);
    }

    /**
     * Reverse-proxy path prefix stripped before forwarding, if declared.
     * @return Prefix without a trailing slash, or empty string
     */
    private String forwardedPrefix() {
        return this.first("X-Forwarded-Prefix")
            .map(ClientBaseUrl::withoutTrailingSlash)
            .orElse("");
    }

    /**
     * First non-blank value of a header, taking the leftmost entry of a
     * comma-separated forwarded chain.
     * @param name Header name
     * @return Header value
     */
    private Optional<String> first(final String name) {
        final List<String> values = this.headers.values(name);
        Optional<String> result = Optional.empty();
        if (!values.isEmpty()) {
            final String raw = values.get(0);
            if (raw != null && !raw.isBlank()) {
                final int comma = raw.indexOf(',');
                final String single;
                if (comma >= 0) {
                    single = raw.substring(0, comma);
                } else {
                    single = raw;
                }
                result = Optional.of(single.trim());
            }
        }
        return result;
    }

    /**
     * Drop a single trailing slash so concatenation never doubles it.
     * @param value Path or prefix
     * @return Value without a trailing slash
     */
    private static String withoutTrailingSlash(final String value) {
        final String result;
        if (value.length() > 1 && value.endsWith("/")) {
            result = value.substring(0, value.length() - 1);
        } else if ("/".equals(value)) {
            result = "";
        } else {
            result = value;
        }
        return result;
    }
}
