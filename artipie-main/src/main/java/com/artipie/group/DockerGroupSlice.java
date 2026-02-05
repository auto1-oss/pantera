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
import java.util.regex.Pattern;

/**
 * Docker-specific group slice with metadata merging support.
 * Wraps GroupSlice with Docker-specific metadata path detection and merging.
 *
 * <p>Docker metadata paths detected:
 * <ul>
 *   <li>Tag lists: paths matching {@code /v2/.+/tags/list}</li>
 *   <li>Catalog: paths matching {@code /v2/_catalog}</li>
 * </ul>
 *
 * <p>Artifact requests (blobs, manifests) use standard race strategy (first success wins).
 *
 * <p>Note: This is a basic implementation. Docker metadata merging is complex due to
 * manifest content addressing - a more sophisticated implementation may be needed
 * for production use.
 *
 * @since 1.18.0
 */
public final class DockerGroupSlice implements Slice {

    /**
     * Pattern for tag list endpoint: /v2/repo/name/tags/list
     */
    private static final Pattern TAG_LIST = Pattern.compile("/v2/.+/tags/list$");

    /**
     * Catalog endpoint: /v2/_catalog
     */
    private static final String CATALOG = "/v2/_catalog";

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
    public DockerGroupSlice(
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
            new DockerTagListMerger(),
            "docker",
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
    public DockerGroupSlice(
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
            "docker",
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
     * Create metadata path detector predicate for Docker.
     *
     * @return Predicate that returns true for Docker metadata paths
     */
    static Predicate<String> createMetadataPathDetector() {
        return path -> {
            if (path == null || path.isEmpty()) {
                return false;
            }
            // Tag list: /v2/library/alpine/tags/list
            if (TAG_LIST.matcher(path).matches()) {
                return true;
            }
            // Catalog: /v2/_catalog
            if (path.equals(CATALOG) || path.startsWith(CATALOG + "?")) {
                return true;
            }
            return false;
        };
    }

    /**
     * Basic Docker tag list merger.
     * Merges tag lists from multiple registries by combining and deduplicating tags.
     */
    private static final class DockerTagListMerger implements MetadataMerger {

        @Override
        public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
            if (responses.isEmpty()) {
                return "{\"name\":\"\",\"tags\":[]}".getBytes(StandardCharsets.UTF_8);
            }

            // Parse and merge tags from all responses
            final java.util.TreeSet<String> allTags = new java.util.TreeSet<>();
            String name = "";

            for (final byte[] data : responses.values()) {
                final String json = new String(data, StandardCharsets.UTF_8);
                // Simple JSON parsing for tag list format: {"name":"repo","tags":["v1","v2"]}
                final int nameStart = json.indexOf("\"name\"");
                if (nameStart >= 0 && name.isEmpty()) {
                    final int colonIdx = json.indexOf(':', nameStart);
                    final int quoteStart = json.indexOf('"', colonIdx);
                    final int quoteEnd = json.indexOf('"', quoteStart + 1);
                    if (quoteStart >= 0 && quoteEnd > quoteStart) {
                        name = json.substring(quoteStart + 1, quoteEnd);
                    }
                }

                final int tagsStart = json.indexOf("\"tags\"");
                if (tagsStart >= 0) {
                    final int bracketStart = json.indexOf('[', tagsStart);
                    final int bracketEnd = json.indexOf(']', bracketStart);
                    if (bracketStart >= 0 && bracketEnd > bracketStart) {
                        final String tagsStr = json.substring(bracketStart + 1, bracketEnd);
                        // Extract tags (simple parsing, assumes no nested quotes)
                        int pos = 0;
                        while (pos < tagsStr.length()) {
                            final int start = tagsStr.indexOf('"', pos);
                            if (start < 0) {
                                break;
                            }
                            final int end = tagsStr.indexOf('"', start + 1);
                            if (end < 0) {
                                break;
                            }
                            allTags.add(tagsStr.substring(start + 1, end));
                            pos = end + 1;
                        }
                    }
                }
            }

            // Build merged response
            final StringBuilder result = new StringBuilder();
            result.append("{\"name\":\"").append(escapeJson(name)).append("\",\"tags\":[");
            boolean first = true;
            for (final String tag : allTags) {
                if (!first) {
                    result.append(',');
                }
                result.append('"').append(escapeJson(tag)).append('"');
                first = false;
            }
            result.append("]}");

            return result.toString().getBytes(StandardCharsets.UTF_8);
        }

        /**
         * Escape JSON string.
         *
         * @param str String to escape
         * @return Escaped string
         */
        private static String escapeJson(final String str) {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }
    }
}
