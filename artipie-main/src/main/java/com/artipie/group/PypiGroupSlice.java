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

/**
 * PyPI-specific group slice with metadata merging support.
 * Wraps GroupSlice with PyPI-specific metadata path detection and merging.
 *
 * <p>PyPI metadata paths detected:
 * <ul>
 *   <li>Simple index: paths containing {@code /simple/}</li>
 *   <li>Package index: paths matching {@code /simple/PACKAGE/} or {@code /simple/PACKAGE}</li>
 * </ul>
 *
 * <p>Artifact requests (wheel/sdist files) use standard race strategy (first success wins).
 *
 * @since 1.18.0
 */
public final class PypiGroupSlice implements Slice {

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
    public PypiGroupSlice(
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
            loadPypiMerger(),
            "pypi",
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
    public PypiGroupSlice(
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
            "pypi",
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
     * Create metadata path detector predicate for PyPI.
     *
     * @return Predicate that returns true for PyPI metadata paths
     */
    static Predicate<String> createMetadataPathDetector() {
        return path -> {
            if (path == null || path.isEmpty()) {
                return false;
            }
            // Simple index pages: /simple/, /simple/package-name/
            // These are HTML pages listing available versions
            if (path.contains("/simple/")) {
                // Exclude actual package files (*.whl, *.tar.gz, etc.)
                final String lower = path.toLowerCase();
                if (lower.endsWith(".whl") || lower.endsWith(".tar.gz")
                    || lower.endsWith(".zip") || lower.endsWith(".egg")) {
                    return false;
                }
                return true;
            }
            // Root simple index: /simple (without trailing slash)
            if (path.equals("/simple")) {
                return true;
            }
            return false;
        };
    }

    /**
     * Load PypiMetadataMerger via reflection to avoid circular dependency.
     *
     * @return MetadataMerger instance
     */
    private static MetadataMerger loadPypiMerger() {
        try {
            final Class<?> clazz = Class.forName(
                "com.artipie.pypi.meta.PypiMetadataMerger"
            );
            return (MetadataMerger) clazz.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "Failed to load PypiMetadataMerger. Ensure pypi-adapter is on the classpath.",
                ex
            );
        }
    }
}
