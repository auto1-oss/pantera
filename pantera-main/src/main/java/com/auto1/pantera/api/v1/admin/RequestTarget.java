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
package com.auto1.pantera.api.v1.admin;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * A client URL (or repo-relative path) resolved to the repository it
 * addresses and the path within it.
 *
 * <p>Accepted shapes: a full client URL with host, optional global prefix
 * and optional {@code api/} segment
 * ({@code https://host/prefix/api/npm_group/lodash}), a server path
 * ({@code /prefix/api/npm_group/lodash}) or a repo-relative path
 * ({@code /npm_group/lodash}). The repository is the first of the leading
 * path segments that names a configured repository.</p>
 *
 * @param repo Addressed repository
 * @param rawPath Repository-relative raw (percent-encoded) path, starts with
 *  {@code /}, query included
 * @param path Repository-relative decoded path without query, starts with
 *  {@code /}
 * @since 2.2.9
 */
public record RequestTarget(RepoTopology.RepoInfo repo, String rawPath, String path) {

    /**
     * How many leading segments (prefix, {@code api}, format) may precede
     * the repository name.
     */
    private static final int MAX_LEADING = 3;

    /**
     * Resolves client URLs against a topology.
     *
     * @since 2.2.9
     */
    public static final class Parser {

        /**
         * Topology.
         */
        private final RepoTopology topology;

        /**
         * Ctor.
         *
         * @param topology Repository topology
         */
        public Parser(final RepoTopology topology) {
            this.topology = topology;
        }

        /**
         * Resolve a URL.
         *
         * @param url Full URL or path
         * @return Target, empty when no configured repository is addressed
         * @throws IllegalArgumentException When the URL is malformed
         */
        public Optional<RequestTarget> parse(final String url) {
            final String trimmed = url == null ? "" : url.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("url is required");
            }
            final URI uri;
            try {
                if (trimmed.contains("://")) {
                    uri = new URI(trimmed);
                } else {
                    uri = new URI(Parser.pathOnly(trimmed));
                }
            } catch (final URISyntaxException ex) {
                throw new IllegalArgumentException("Malformed url: " + ex.getMessage(), ex);
            }
            final String raw = uri.getRawPath() == null ? "/" : uri.getRawPath();
            final String[] segments = raw.replaceFirst("^/+", "").split("/", -1);
            final int limit = Math.min(MAX_LEADING, segments.length - 1);
            for (int idx = 0; idx <= limit; idx = idx + 1) {
                final String name = Parser.decode(segments[idx]);
                final Optional<RepoTopology.RepoInfo> repo = this.topology.repo(name);
                if (repo.isPresent()) {
                    final StringBuilder rest = new StringBuilder();
                    for (int pos = idx + 1; pos < segments.length; pos = pos + 1) {
                        rest.append('/').append(segments[pos]);
                    }
                    final String restRaw = rest.length() == 0 ? "/" : rest.toString();
                    final String query = uri.getRawQuery();
                    return Optional.of(new RequestTarget(
                        repo.get(),
                        query == null ? restRaw : restRaw + "?" + query,
                        Parser.decode(restRaw)
                    ));
                }
            }
            return Optional.empty();
        }

        /**
         * Path with a leading slash; a query/fragment is kept as typed.
         *
         * @param value Input
         * @return Path
         */
        private static String pathOnly(final String value) {
            return value.startsWith("/") ? value : "/" + value;
        }

        /**
         * Percent-decode a path (never '+' to space).
         *
         * @param raw Raw path
         * @return Decoded path
         */
        private static String decode(final String raw) {
            try {
                return java.net.URLDecoder.decode(
                    raw.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8
                );
            } catch (final IllegalArgumentException ex) {
                return raw;
            }
        }
    }
}
