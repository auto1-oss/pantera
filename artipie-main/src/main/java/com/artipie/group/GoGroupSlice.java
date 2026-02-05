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
 * Go-specific group slice with metadata merging support.
 * Wraps GroupSlice with Go-specific metadata path detection and merging.
 *
 * <p>Go metadata paths detected:
 * <ul>
 *   <li>Version lists: paths containing {@code /@v/list}</li>
 *   <li>Version info: paths ending with {@code .info}</li>
 *   <li>Module files: paths ending with {@code .mod}</li>
 * </ul>
 *
 * <p>Artifact requests (zip files) use standard race strategy (first success wins).
 *
 * @since 1.18.0
 */
public final class GoGroupSlice implements Slice {

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
    public GoGroupSlice(
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
            loadGoMerger(),
            "go",
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
    public GoGroupSlice(
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
            "go",
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
     * Create metadata path detector predicate for Go.
     *
     * @return Predicate that returns true for Go metadata paths
     */
    static Predicate<String> createMetadataPathDetector() {
        return path -> {
            if (path == null || path.isEmpty()) {
                return false;
            }
            // Version list: github.com/pkg/errors/@v/list
            if (path.contains("/@v/list")) {
                return true;
            }
            // Version info: github.com/pkg/errors/@v/v0.9.1.info
            if (path.endsWith(".info")) {
                return true;
            }
            // Module file: github.com/pkg/errors/@v/v0.9.1.mod
            if (path.endsWith(".mod")) {
                return true;
            }
            return false;
        };
    }

    /**
     * Load GoMetadataMerger via reflection to avoid circular dependency.
     *
     * @return MetadataMerger instance
     */
    private static MetadataMerger loadGoMerger() {
        try {
            final Class<?> clazz = Class.forName(
                "com.artipie.goproxy.metadata.GoMetadataMerger"
            );
            return (MetadataMerger) clazz.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "Failed to load GoMetadataMerger. Ensure go-adapter is on the classpath.",
                ex
            );
        }
    }
}
