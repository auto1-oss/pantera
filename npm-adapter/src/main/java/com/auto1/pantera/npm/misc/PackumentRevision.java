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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Deterministic revision fingerprint for an NPM packument, derived from the
 * on-disk storage state that {@link com.auto1.pantera.npm.PerVersionLayout}
 * owns: the set of {@code .versions/*.json} files and the {@code
 * .dist-tags.json} sidecar. Real npm registries hand out an opaque {@code
 * _rev} with every packument so that {@code npm unpublish --force} can be
 * validated against the revision the client actually fetched; Pantera's
 * packuments carry none, so this class computes an equivalent fingerprint
 * on demand instead of persisting one.
 *
 * @since 2.2.5
 */
public final class PackumentRevision {

    /**
     * Field separator that cannot occur in a semver string or a dist-tag name.
     */
    private static final char SEP = '\n';

    /**
     * Section separator between the versions block and the tags block.
     */
    private static final char SECTION = '\u0001';

    /**
     * Storage holding the package.
     */
    private final Storage storage;

    /**
     * Package name (storage prefix).
     */
    private final String pkg;

    /**
     * Ctor.
     * @param storage Storage holding the package
     * @param pkg Package name used as the storage prefix
     */
    public PackumentRevision(final Storage storage, final String pkg) {
        this.storage = storage;
        this.pkg = pkg;
    }

    /**
     * Compute the revision for the current storage state.
     * @return Revision in the form {@code <versionCount>-<32 hex chars>}
     */
    public CompletableFuture<String> value() {
        final Key versions = new Key.From(this.pkg, ".versions");
        return this.storage.list(versions).thenCompose(
            keys -> this.tags().thenApply(
                tags -> {
                    final SortedSet<String> names = new TreeSet<>();
                    for (final Key key : keys) {
                        final String leaf = key.string()
                            .substring(key.string().lastIndexOf('/') + 1);
                        if (leaf.endsWith(".json")) {
                            names.add(leaf.substring(0, leaf.length() - ".json".length()));
                        }
                    }
                    return String.format(
                        "%d-%s", names.size(),
                        PackumentRevision.digest(PackumentRevision.canonical(names, tags))
                    );
                }
            )
        );
    }

    /**
     * Read the dist-tags document, empty when absent.
     * @return Tag name to version mapping, sorted by tag name
     */
    private CompletableFuture<SortedMap<String, String>> tags() {
        final Key key = new Key.From(this.pkg, ".dist-tags.json");
        return this.storage.exists(key).thenCompose(
            exists -> {
                final CompletableFuture<SortedMap<String, String>> result;
                if (exists) {
                    result = this.storage.value(key)
                        .thenCompose(Content::asJsonObjectFuture)
                        .thenApply(
                            json -> {
                                final SortedMap<String, String> map = new TreeMap<>();
                                for (final String tag : json.keySet()) {
                                    map.put(tag, json.getString(tag, ""));
                                }
                                return map;
                            }
                        );
                } else {
                    result = CompletableFuture.completedFuture(new TreeMap<>());
                }
                return result;
            }
        );
    }

    /**
     * Build the canonical serialization both readers must agree on.
     * @param versions Sorted version names
     * @param tags Sorted tag mapping
     * @return Canonical string
     */
    private static String canonical(
        final SortedSet<String> versions, final SortedMap<String, String> tags
    ) {
        final StringBuilder out = new StringBuilder();
        for (final String version : versions) {
            out.append(version).append(PackumentRevision.SEP);
        }
        out.append(PackumentRevision.SECTION);
        for (final Map.Entry<String, String> entry : tags.entrySet()) {
            out.append(entry.getKey()).append(PackumentRevision.SEP)
                .append(entry.getValue()).append(PackumentRevision.SEP);
        }
        return out.toString();
    }

    /**
     * First 32 hex characters of the SHA-256 digest.
     * @param input Canonical string
     * @return 32 lowercase hex characters
     */
    private static String digest(final String input) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int idx = 0; idx < 16; idx = idx + 1) {
                hex.append(String.format("%02x", hash[idx]));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
