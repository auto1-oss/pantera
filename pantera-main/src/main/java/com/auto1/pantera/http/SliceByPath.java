/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.RepositorySlices;
import com.auto1.pantera.RqPath;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.settings.PrefixesConfig;

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
            .response(effectiveLine, headers, body);
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
