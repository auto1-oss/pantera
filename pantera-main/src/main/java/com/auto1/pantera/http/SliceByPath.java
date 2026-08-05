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

import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.RqPath;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.PrefixesConfig;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.repo.Repositories;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Slice which finds repository by path.
 * Supports global URL prefixes for migration scenarios.
 */
final class SliceByPath implements Slice {

    /**
     * Slices cache.
     */
    private final RepositorySlices slices;

    /**
     * Global prefixes configuration.
     */
    private final PrefixesConfig prefixes;

    /**
     * Create SliceByPath.
     *
     * @param slices Slices cache
     * @param prefixes Global prefixes configuration
     */
    SliceByPath(final RepositorySlices slices, final PrefixesConfig prefixes) {
        this.slices = slices;
        this.prefixes = prefixes;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final String originalPath = line.uri().getPath();
        final String strippedPath = this.stripPrefix(originalPath);

        // If path was modified, create new RequestLine preserving query too
        final RequestLine effectiveLine;
        if (strippedPath.equals(originalPath)) {
            effectiveLine = line;
        } else {
            final String query = line.uri().getQuery();
            final StringBuilder uri = new StringBuilder(strippedPath);
            if (query != null && !query.isEmpty()) {
                uri.append('?').append(query);
            }
            effectiveLine = new RequestLine(
                line.method().value(),
                uri.toString(),
                line.version()
            );
        }
        
        final Optional<Key> key = SliceByPath.keyFromPath(strippedPath);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseBuilder.notFound()
                .textBody("Failed to find a repository")
                .build()
            );
        }
        return this.slices.slice(key.get(), effectiveLine.uri().getPort())
            .response(
                effectiveLine,
                this.stamped(headers, key.get(), originalPath, strippedPath),
                body
            );
    }

    /**
     * Stamp the addressed repository's client-facing base URL, unless an
     * outer slice already did. Stamping here — above the group resolver —
     * is what makes a group member emit URLs under the <em>group</em>.
     *
     * @param headers Inbound headers
     * @param key Resolved repository key
     * @param originalPath Request path before prefix stripping
     * @param strippedPath Request path after prefix stripping
     * @return Headers, with the base stamped when derivable
     */
    private Headers stamped(
        final Headers headers, final Key key,
        final String originalPath, final String strippedPath
    ) {
        final ClientBaseUrl base = new ClientBaseUrl(headers);
        final Headers result;
        if (base.stamped().isPresent()) {
            result = headers;
        } else {
            final Optional<String> value = this.configured(key)
                .or(() -> base.derive(
                    SliceByPath.clientPath(headers, originalPath),
                    SliceByPath.remainder(key, strippedPath)
                ));
            if (value.isPresent()) {
                result = headers.copy().add(new Header(ClientBaseUrl.HEADER, value.get()));
            } else {
                result = headers;
            }
        }
        return result;
    }

    /**
     * Explicitly configured {@code url:} of the addressed repository. Only the
     * addressed repository is consulted — never a group member's own URL,
     * which is the bug this whole mechanism exists to fix.
     *
     * @param key Repository key
     * @return Configured URL, or empty
     */
    private Optional<String> configured(final Key key) {
        Optional<String> result = Optional.empty();
        final Repositories repos = this.slices.repositories();
        if (repos != null) {
            result = repos.config(key.string()).flatMap(RepoConfig::urlOpt);
        }
        return result;
    }

    /**
     * Path as the client sent it: the pre-rewrite path when
     * {@code ApiRoutingSlice} recorded one, else the current path.
     *
     * @param headers Inbound headers
     * @param originalPath Current request path
     * @return Client-facing path
     */
    private static String clientPath(final Headers headers, final String originalPath) {
        final List<String> recorded = headers.values(ClientBaseUrl.ORIGINAL_PATH);
        final String result;
        if (recorded.isEmpty() || recorded.get(0) == null || recorded.get(0).isBlank()) {
            result = originalPath;
        } else {
            result = recorded.get(0);
        }
        return result;
    }

    /**
     * Path relative to the repository, e.g. {@code /pnpm} for
     * {@code /npm_group/pnpm}.
     *
     * @param key Repository key
     * @param strippedPath Path after global-prefix stripping
     * @return Repository-relative remainder
     */
    private static String remainder(final Key key, final String strippedPath) {
        final String prefixed = "/" + key.string();
        final String result;
        if (strippedPath.length() > prefixed.length() && strippedPath.startsWith(prefixed)) {
            result = strippedPath.substring(prefixed.length());
        } else {
            result = "";
        }
        return result;
    }

    /**
     * Strip configured prefix from path if present.
     * Only strips if first segment matches a configured prefix.
     * Validates that only one prefix is present.
     *
     * @param path Original request path
     * @return Path with prefix stripped, or original if no prefix matched
     */
    private String stripPrefix(final String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return path;
        }

        // Find first non-slash index
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        if (start >= path.length()) {
            return path;
        }

        // Determine first path segment boundaries in the original path
        final int next = path.indexOf('/', start);
        final String first = next == -1 ? path.substring(start) : path.substring(start, next);

        if (this.prefixes.isPrefix(first)) {
            // If only the prefix is present, return root '/'
            if (next == -1) {
                return "/";
            }
            // Return the remainder starting from the slash before the next segment
            return path.substring(next);
        }

        return path;
    }

    /**
     * Repository key from path.
     * @param path Path to get repository key from
     * @return Key if found
     */
    private static Optional<Key> keyFromPath(final String path) {
        final String[] parts = SliceByPath.splitPath(path);
        if (RqPath.CONDA.test(path)) {
            return Optional.of(new Key.From(parts[2]));
        }
        if (parts.length >= 1 && !parts[0].isBlank()) {
            return Optional.of(new Key.From(parts[0]));
        }
        return Optional.empty();
    }

    /**
     * Split path into parts.
     *
     * @param path Path.
     * @return Array of path parts.
     */
    private static String[] splitPath(final String path) {
        return path.replaceAll("^/+", "").split("/");
    }
}
