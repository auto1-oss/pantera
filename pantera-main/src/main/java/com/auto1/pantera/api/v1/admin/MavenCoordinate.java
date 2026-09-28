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

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@code groupId:artifactId} of a maven-layout package name.
 *
 * <p>The artifacts index and the cooldown tables store maven names dotted
 * ({@code com.fasterxml.jackson.core.jackson-databind}), which loses where
 * the groupId ends. The index path prefix
 * ({@code com/fasterxml/jackson/core/jackson-databind/2.17.0}) keeps it: the
 * split is the one whose {@code groupId.artifactId} equals the dotted
 * name, with the version and file segments dropped.</p>
 *
 * @since 2.2.9
 */
public final class MavenCoordinate {

    /**
     * Trailing path segments tried away: none, the version, version + file.
     */
    private static final int MAX_DROPPED = 2;

    /**
     * Name as stored.
     */
    private final String name;

    /**
     * Index path prefix, may be null.
     */
    private final String path;

    /**
     * Ctor.
     *
     * @param name Name as stored (dotted, slash-separated or already
     *  {@code groupId:artifactId})
     * @param path Index path prefix, may be null
     */
    public MavenCoordinate(final String name, final String path) {
        this.name = name == null ? "" : name.trim();
        this.path = path;
    }

    /**
     * Coordinate.
     *
     * @return {@code groupId:artifactId}, empty when the split is unknown
     */
    public Optional<String> coordinate() {
        final Optional<String> result;
        if (this.name.indexOf(':') > 0) {
            result = Optional.of(this.name);
        } else if (this.name.indexOf('/') > 0) {
            result = MavenCoordinate.split(this.name.replace('/', '.'), this.name);
        } else if (this.path == null || this.path.isBlank()) {
            result = Optional.empty();
        } else {
            result = MavenCoordinate.split(this.name, this.path);
        }
        return result;
    }

    /**
     * The split of a path that spells the dotted name.
     *
     * @param dotted Dotted name
     * @param path Path
     * @return Coordinate, empty when no split spells the name
     */
    private static Optional<String> split(final String dotted, final String path) {
        final String[] segs = Arrays.stream(path.split("/"))
            .filter(seg -> !seg.isEmpty()).toArray(String[]::new);
        Optional<String> result = Optional.empty();
        for (int drop = 0; drop <= MAX_DROPPED && result.isEmpty(); drop += 1) {
            final int len = segs.length - drop;
            if (len >= 2) {
                final String group = String.join(".", Arrays.copyOfRange(segs, 0, len - 1));
                final String artifact = segs[len - 1];
                if ((group + "." + artifact).equalsIgnoreCase(dotted)) {
                    result = Optional.of(group + ":" + artifact);
                }
            }
        }
        return result;
    }
}
