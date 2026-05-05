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

import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.prefetch.Coordinate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * npm prefetch parser that dispatches to a tarball or packument sub-parser
 * based on the on-disk file shape. The npm-proxy fires
 * {@link com.auto1.pantera.http.cache.CacheWriteEvent} for both tarball
 * cache writes (Phase 9) and packument cache writes (Phase 13); both
 * arrive on the same {@code "npm-proxy"} repo type so the dispatcher
 * needs a single parser that handles both.
 *
 * <h2>Shape detection</h2>
 * <p>Read the first byte of the file:</p>
 * <ul>
 *   <li>{@code 0x1F} (followed by {@code 0x8B}) — gzip magic, i.e. an
 *       npm tarball ({@code .tgz}). Delegate to
 *       {@link NpmPackageParser}.</li>
 *   <li>Anything else — assume JSON (packument {@code meta.json}).
 *       Delegate to {@link NpmPackumentParser}.</li>
 * </ul>
 * <p>The two-byte gzip magic is unambiguous against JSON (which starts
 * with whitespace, {@code {}, or BOM bytes) so this is a safe binary
 * detection. We only need the first byte for the dispatch decision.</p>
 *
 * <h2>Failure containment</h2>
 * <p>On I/O failure reading the magic byte, the parser logs WARN and
 * returns an empty list. Sub-parser failures are already self-contained.</p>
 *
 * @since 2.2.0
 */
public final class NpmCompositeParser implements PrefetchParser {

    /** First byte of the gzip magic ({@code 0x1F 0x8B}). */
    private static final int GZIP_MAGIC_0 = 0x1F;

    /** Logger name. */
    private static final String LOGGER = "com.auto1.pantera.prefetch.NpmCompositeParser";

    /**
     * Tarball sub-parser. Resolves dep ranges via the metadata lookup
     * supplied at construction.
     */
    private final NpmPackageParser tarballParser;

    /**
     * Packument sub-parser. Stateless.
     */
    private final NpmPackumentParser packumentParser;

    /**
     * Build a composite npm parser bound to the given metadata-cache lookup.
     *
     * @param lookup Metadata lookup used by the tarball sub-parser to
     *               resolve dependency ranges to concrete versions. The
     *               packument sub-parser does not need it (it emits
     *               version-less packument coordinates).
     */
    public NpmCompositeParser(final NpmMetadataLookup lookup) {
        this.tarballParser = new NpmPackageParser(lookup);
        this.packumentParser = new NpmPackumentParser();
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public List<Coordinate> parse(final Path bytesOnDisk) {
        final boolean tarball;
        try {
            tarball = isGzip(bytesOnDisk);
        } catch (final IOException ex) {
            EcsLogger.warn(LOGGER)
                .message("npm composite parser: failed to read magic byte")
                .eventCategory("file")
                .eventAction("parse")
                .eventOutcome("failure")
                .field("file.path", bytesOnDisk.toString())
                .field("error.type", ex.getClass().getName())
                .log();
            return List.of();
        }
        if (tarball) {
            return this.tarballParser.parse(bytesOnDisk);
        }
        return this.packumentParser.parse(bytesOnDisk);
    }

    /**
     * Read the first byte of {@code path} and return {@code true} when it
     * matches the gzip magic.
     */
    private static boolean isGzip(final Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            final int first = in.read();
            return first == GZIP_MAGIC_0;
        }
    }
}
