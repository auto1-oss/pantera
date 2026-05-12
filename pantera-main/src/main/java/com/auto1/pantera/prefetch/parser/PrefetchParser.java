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
package com.auto1.pantera.prefetch.parser;

import com.auto1.pantera.prefetch.Coordinate;
import java.nio.file.Path;
import java.util.List;

/**
 * Strategy that extracts prefetch coordinates from a manifest already
 * persisted on disk (a freshly cached pom, package.json, etc.).
 *
 * <p>Implementations must be safe to invoke from any thread, must not
 * throw on malformed input (return an empty list and log instead), and
 * should restrict themselves to direct/runtime dependencies — transitive
 * resolution is the dispatcher's job.</p>
 *
 * @since 2.2.0
 */
public interface PrefetchParser {

    /**
     * Extract direct prefetch coordinates from a manifest file.
     *
     * @param bytesOnDisk Path to the cached manifest (must be readable).
     * @return Direct dependency coordinates; empty if file is missing or malformed.
     */
    List<Coordinate> parse(Path bytesOnDisk);

    /**
     * Filter: should this parser even attempt the cached artifact at
     * {@code urlPath}? Pre-Track-5 the dispatcher routed every cached
     * primary to the registered parser regardless of extension — for the
     * Maven adapter that meant {@link MavenPomParser} was invoked on
     * every cached {@code .jar} (binary ZIP starting with {@code PK}),
     * producing one WARN per jar and burning CPU on a guaranteed-failed
     * XML parse. {@code .jar} writes in a {@code mvn dependency:resolve}
     * cold walk easily account for hundreds of these log lines and a
     * meaningful slice of the wall clock.
     *
     * <p>Default returns {@code true} for backward-compatibility with
     * parsers that don't care about path filtering (npm tarball parser,
     * etc.). Maven and Gradle override to gate on {@code .pom}.
     *
     * @param urlPath URL path of the cached artifact (e.g.
     *                {@code com/example/foo/1.0/foo-1.0.jar}).
     * @return {@code true} iff this parser can usefully process the file.
     */
    default boolean appliesTo(final String urlPath) {
        return true;
    }
}
