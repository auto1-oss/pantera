/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.group;

import com.artipie.asto.Content;
import com.artipie.cache.GroupSettings;
import com.artipie.cache.MetadataMerger;
import com.artipie.cache.UnifiedGroupCache;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Composer-specific group slice with metadata merging support.
 * Wraps GroupSlice with Composer-specific metadata path detection and merging.
 *
 * <p>Composer metadata paths detected:
 * <ul>
 *   <li>Main packages file: paths containing {@code /packages.json}</li>
 *   <li>Provider includes: paths containing {@code /p/} or {@code /p2/}</li>
 * </ul>
 *
 * <p>Artifact requests (zip files) use standard race strategy (first success wins).
 *
 * <p>Note: This is a basic implementation. Composer metadata merging is complex due to
 * the packages.json structure with provider references. A more sophisticated implementation
 * may be needed for production use.
 *
 * @since 1.18.0
 */
public final class ComposerGroupSlice implements Slice {

    /**
     * Delegate GroupSlice with metadata merging configured.
     */
    private final Slice delegate;

    /**
     * Constructor with UnifiedGroupCache and GroupSettings.
     *
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members Member repository names
     * @param port Server port
     * @param unifiedCache Unified group cache for metadata merging
     * @param settings Group settings
     */
    public ComposerGroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final UnifiedGroupCache unifiedCache,
        final GroupSettings settings
    ) {
        Objects.requireNonNull(resolver, "resolver cannot be null");
        Objects.requireNonNull(group, "group cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        Objects.requireNonNull(unifiedCache, "unifiedCache cannot be null");
        Objects.requireNonNull(settings, "settings cannot be null");

        this.delegate = GroupSlice.withMetadataMerging(
            resolver,
            group,
            members,
            port,
            unifiedCache,
            new ComposerPackagesMerger(),
            "composer",
            createMetadataPathDetector()
        );
    }

    /**
     * Constructor with explicit MetadataMerger (for testing).
     *
     * @param resolver Slice resolver
     * @param group Group repository name
     * @param members Member repository names
     * @param port Server port
     * @param unifiedCache Unified group cache for metadata merging
     * @param merger Metadata merger
     */
    public ComposerGroupSlice(
        final SliceResolver resolver,
        final String group,
        final List<String> members,
        final int port,
        final UnifiedGroupCache unifiedCache,
        final MetadataMerger merger
    ) {
        Objects.requireNonNull(resolver, "resolver cannot be null");
        Objects.requireNonNull(group, "group cannot be null");
        Objects.requireNonNull(members, "members cannot be null");
        Objects.requireNonNull(unifiedCache, "unifiedCache cannot be null");
        Objects.requireNonNull(merger, "merger cannot be null");

        this.delegate = GroupSlice.withMetadataMerging(
            resolver,
            group,
            members,
            port,
            unifiedCache,
            merger,
            "composer",
            createMetadataPathDetector()
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        return this.delegate.response(line, headers, body);
    }

    /**
     * Create metadata path detector predicate for Composer.
     *
     * @return Predicate that returns true for Composer metadata paths
     */
    static Predicate<String> createMetadataPathDetector() {
        return path -> {
            if (path == null || path.isEmpty()) {
                return false;
            }
            // Main packages file: /packages.json
            if (path.endsWith("/packages.json") || path.equals("/packages.json")) {
                return true;
            }
            // Provider files: /p/vendor/package.json, /p2/vendor/package.json
            if (path.contains("/p/") || path.contains("/p2/")) {
                // Only JSON files are metadata
                if (path.endsWith(".json")) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * Basic Composer packages.json merger.
     * Merges packages metadata from multiple Composer repositories.
     *
     * <p>Composer packages.json has structure:
     * <pre>
     * {
     *   "packages": {
     *     "vendor/package": {
     *       "1.0.0": { ... version metadata ... },
     *       "2.0.0": { ... version metadata ... }
     *     }
     *   }
     * }
     * </pre>
     */
    private static final class ComposerPackagesMerger implements MetadataMerger {

        @Override
        public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
            if (responses.isEmpty()) {
                return "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
            }

            // For simplicity, use first response as base and merge others
            // A full implementation would parse JSON and merge packages/versions
            final java.util.Map<String, java.util.Map<String, String>> packages =
                new java.util.TreeMap<>();

            for (final byte[] data : responses.values()) {
                final String json = new String(data, StandardCharsets.UTF_8);
                // Simple JSON extraction - find "packages" object
                final int packagesStart = json.indexOf("\"packages\"");
                if (packagesStart >= 0) {
                    // For basic implementation, merge first valid response
                    // Full implementation would parse and merge all packages
                    if (packages.isEmpty()) {
                        return data; // Return first valid response
                    }
                }
            }

            // If no valid response found, return empty packages
            return "{\"packages\":{}}".getBytes(StandardCharsets.UTF_8);
        }
    }
}
