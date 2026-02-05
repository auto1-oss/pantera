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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * NPM-specific group slice with metadata merging support.
 * Wraps GroupSlice with NPM-specific metadata path detection and merging.
 *
 * <p>NPM metadata paths detected:
 * <ul>
 *   <li>Package metadata: {@code ^/[^/]+$} or {@code ^/@[^/]+/[^/]+$} (scoped packages)</li>
 *   <li>Package.json requests: paths ending with {@code package.json}</li>
 * </ul>
 *
 * <p>Artifact requests (tarballs) use standard race strategy (first success wins).
 *
 * @since 1.18.0
 */
public final class NpmGroupSlice implements Slice {

    /**
     * Pattern for unscoped package metadata: /package-name (single path segment).
     */
    private static final Pattern UNSCOPED_PKG = Pattern.compile("^/[^/@][^/]*$");

    /**
     * Pattern for scoped package metadata: /@scope/package-name (two segments, first starts with @).
     */
    private static final Pattern SCOPED_PKG = Pattern.compile("^/@[^/]+/[^/]+$");

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
    public NpmGroupSlice(
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
            loadNpmMerger(),
            "npm",
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
    public NpmGroupSlice(
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
            "npm",
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
     * Create metadata path detector predicate for NPM.
     *
     * @return Predicate that returns true for NPM metadata paths
     */
    static Predicate<String> createMetadataPathDetector() {
        return path -> {
            if (path == null || path.isEmpty()) {
                return false;
            }
            // Package metadata requests:
            // - Unscoped: /lodash, /express (single path segment, no @)
            // - Scoped: /@types/node, /@babel/core (two segments, first starts with @)
            if (UNSCOPED_PKG.matcher(path).matches()) {
                return true;
            }
            if (SCOPED_PKG.matcher(path).matches()) {
                return true;
            }
            // Package.json requests (e.g., /lodash/package.json)
            if (path.endsWith("/package.json")) {
                return true;
            }
            return false;
        };
    }

    /**
     * Load NpmMetadataMerger via reflection to avoid circular dependency.
     *
     * @return MetadataMerger instance
     */
    private static MetadataMerger loadNpmMerger() {
        try {
            final Class<?> clazz = Class.forName(
                "com.artipie.npm.metadata.NpmMetadataMerger"
            );
            return (MetadataMerger) clazz.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "Failed to load NpmMetadataMerger. Ensure npm-adapter is on the classpath.",
                ex
            );
        }
    }
}
