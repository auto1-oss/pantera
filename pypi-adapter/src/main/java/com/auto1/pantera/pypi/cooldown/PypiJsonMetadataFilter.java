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
package com.auto1.pantera.pypi.cooldown;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Filters blocked versions out of a PyPI JSON API response body
 * ({@code /pypi/<name>/json}).
 *
 * <p>Pip resolves {@code pip install foo} (unbounded) by reading
 * {@code info.version} from this document. If the upstream latest is
 * under cooldown we must pick a fallback — otherwise a transparent
 * proxy would silently leak the blocked version to the client, defeating
 * the whole cooldown. Matches the contract Maven's metadata filter
 * already provides for unbounded resolution.</p>
 *
 * <p>Response shape (simplified):</p>
 * <pre>{@code
 * {
 *   "info": {
 *     "name": "requests",
 *     "version": "2.32.0",    // latest per upstream
 *     ...
 *   },
 *   "releases": {
 *     "2.31.0": [ {file1}, ... ],
 *     "2.32.0": [ ... ]
 *   },
 *   "urls": [ ... ]            // files for info.version only
 * }
 * }</pre>
 *
 * <p>Filter algorithm:</p>
 * <ol>
 *   <li>Remove every blocked key from {@code releases}.</li>
 *   <li>If {@code info.version} is not blocked → leave it.</li>
 *   <li>If blocked → pick the highest remaining release key by PEP 440
 *       ordering, overwrite {@code info.version}, and replace
 *       {@code urls} with the files for that key (so the
 *       {@code urls}-to-{@code info.version} invariant holds).</li>
 *   <li>If every version is blocked → return {@link Result#AllBlocked}
 *       so the caller can emit HTTP 404 (pip's convention).</li>
 * </ol>
 *
 * @since 2.2.0
 */
public final class PypiJsonMetadataFilter {

    /**
     * PEP 440 comparator used to pick the highest non-blocked fallback.
     */
    private static final Pep440VersionComparator PEP440 =
        new Pep440VersionComparator();

    /**
     * Jackson mapper. Thread-safe; reused across calls.
     */
    private final ObjectMapper mapper;

    /**
     * Default constructor.
     */
    public PypiJsonMetadataFilter() {
        this(new ObjectMapper());
    }

    /**
     * Ctor with injectable mapper (tests).
     *
     * @param mapper Jackson mapper
     */
    public PypiJsonMetadataFilter(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Filter a PyPI JSON API response body.
     *
     * @param body Raw upstream bytes
     * @param blocked Set of version strings in cooldown for this package
     * @return {@link Result} — either {@code Filtered(bytes)} containing
     *     the rewritten body, {@code Passthrough(bytes)} when the body
     *     cannot be parsed (never break clients on upstream weirdness),
     *     or {@link Result#AllBlocked} when every version is blocked
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public Result filter(final byte[] body, final Set<String> blocked) {
        if (body == null || body.length == 0) {
            return new Passthrough(new byte[0]);
        }
        final JsonNode root;
        try {
            root = this.mapper.readTree(body);
        } catch (final Exception ex) {
            return new Passthrough(body);
        }
        if (!(root instanceof ObjectNode)) {
            return new Passthrough(body);
        }
        final ObjectNode obj = (ObjectNode) root;
        final JsonNode releasesNode = obj.get("releases");
        if (!(releasesNode instanceof ObjectNode)) {
            // No releases map — nothing to filter. Pass through.
            return new Passthrough(body);
        }
        final ObjectNode releases = (ObjectNode) releasesNode;
        if (blocked != null && !blocked.isEmpty()) {
            final Iterator<String> it = releases.fieldNames();
            final List<String> toRemove = new ArrayList<>();
            while (it.hasNext()) {
                final String version = it.next();
                if (blocked.contains(version)) {
                    toRemove.add(version);
                }
            }
            for (final String version : toRemove) {
                releases.remove(version);
            }
        }
        if (releases.size() == 0) {
            // Every version blocked (or upstream had none) → 404.
            return Result.AllBlocked.INSTANCE;
        }
        // Rewrite info.version if the upstream latest is blocked.
        final JsonNode infoNode = obj.get("info");
        if (infoNode instanceof ObjectNode) {
            final ObjectNode info = (ObjectNode) infoNode;
            final JsonNode versionNode = info.get("version");
            if (versionNode != null && versionNode.isTextual()) {
                final String upstreamLatest = versionNode.asText();
                if (blocked != null && blocked.contains(upstreamLatest)) {
                    final Optional<String> fallback = highestRelease(releases);
                    if (fallback.isEmpty()) {
                        return Result.AllBlocked.INSTANCE;
                    }
                    final String picked = fallback.get();
                    info.put("version", picked);
                    // info.urls was for the upstream-latest files. Swap
                    // it to the files for the fallback so the
                    // urls-vs-info invariant holds for whoever reads it.
                    final JsonNode pickedFiles = releases.get(picked);
                    final ArrayNode urls = this.mapper.createArrayNode();
                    if (pickedFiles != null && pickedFiles.isArray()) {
                        urls.addAll((ArrayNode) pickedFiles);
                    }
                    obj.set("urls", urls);
                }
            }
        }
        try {
            return new Filtered(this.mapper.writeValueAsBytes(obj));
        } catch (final Exception ex) {
            return new Passthrough(body);
        }
    }

    /**
     * Pick the highest release key by PEP 440 ordering. Empty when the
     * releases map has no keys after filtering.
     */
    private static Optional<String> highestRelease(final ObjectNode releases) {
        final List<String> keys = new ArrayList<>();
        final Iterator<String> it = releases.fieldNames();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        if (keys.isEmpty()) {
            return Optional.empty();
        }
        return keys.stream().max(Comparator.nullsFirst(PEP440));
    }

    /**
     * Filter outcome — sealed-style hierarchy.
     */
    public sealed interface Result permits Filtered, Passthrough, Result.AllBlocked {

        /**
         * Every version blocked — caller should emit HTTP 404.
         */
        final class AllBlocked implements Result {
            public static final AllBlocked INSTANCE = new AllBlocked();

            private AllBlocked() {
            }
        }
    }

    /**
     * Successfully filtered body. May equal the input bytes when no
     * blocked versions were present (we always re-serialise for simplicity;
     * caller can short-circuit if needed).
     */
    public static final class Filtered implements Result {
        private final byte[] bytes;

        /**
         * @param bytes Rewritten JSON bytes
         */
        public Filtered(final byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        /**
         * @return Defensive copy of the rewritten bytes
         */
        public byte[] bytes() {
            return this.bytes.clone();
        }
    }

    /**
     * Upstream body could not be parsed — forward unchanged.
     */
    public static final class Passthrough implements Result {
        private final byte[] bytes;

        /**
         * @param bytes Original upstream bytes
         */
        public Passthrough(final byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        /**
         * @return Defensive copy of the upstream bytes
         */
        public byte[] bytes() {
            return this.bytes.clone();
        }
    }

    /**
     * Expose the comparator for tests / callers that need PEP 440
     * ordering without instantiating a whole filter.
     *
     * @return Singleton comparator instance
     */
    public static Pep440VersionComparator comparator() {
        return PEP440;
    }

    @SuppressWarnings("unused")
    private static List<String> emptyList() {
        return Collections.emptyList();
    }
}
