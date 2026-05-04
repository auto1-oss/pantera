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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Parses an npm tarball ({@code .tgz} containing {@code package/package.json})
 * and extracts the runtime dependencies as {@link Coordinate}s.
 *
 * <p>Only the top-level {@code dependencies} object is read.
 * {@code devDependencies}, {@code peerDependencies}, and
 * {@code optionalDependencies} are intentionally ignored — pre-fetch warms
 * the cache only for what a typical install will pull at runtime.</p>
 *
 * <p>Each {@code (name, range)} entry is resolved against an
 * {@link NpmMetadataLookup} that consults the local metadata cache.
 * If the lookup returns empty (range not resolvable from local cache),
 * the entry is silently skipped. Pre-fetch must NEVER initiate an
 * upstream metadata fetch.</p>
 *
 * <p>On any I/O or parse failure (missing tarball, missing
 * {@code package.json}, malformed JSON, etc.) the parser logs a WARN with
 * an {@code error.type} field and returns an empty list — it never
 * throws.</p>
 *
 * @since 2.2.0
 */
public final class NpmPackageParser implements PrefetchParser {

    /**
     * Conventional path of the npm manifest inside a published tarball.
     */
    private static final String MANIFEST_ENTRY = "package/package.json";

    /**
     * Shared, thread-safe JSON reader; hoisted to avoid per-call allocation.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Local metadata cache lookup; resolves version ranges to concrete versions.
     */
    private final NpmMetadataLookup lookup;

    /**
     * Build a parser bound to the given metadata-cache lookup.
     *
     * @param lookup Resolves a {@code (name, range)} pair to a concrete
     *               version using the local metadata cache.
     */
    public NpmPackageParser(final NpmMetadataLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public List<Coordinate> parse(final Path bytesOnDisk) {
        try (
            InputStream raw = Files.newInputStream(bytesOnDisk);
            BufferedInputStream buffered = new BufferedInputStream(raw);
            GZIPInputStream gz = new GZIPInputStream(buffered);
            TarArchiveInputStream tar = new TarArchiveInputStream(gz)
        ) {
            final byte[] manifest = readManifest(tar);
            if (manifest == null) {
                EcsLogger.warn("com.auto1.pantera.prefetch")
                    .message("npm tarball missing " + MANIFEST_ENTRY)
                    .eventCategory("file")
                    .eventAction("parse")
                    .eventOutcome("failure")
                    .field("file.path", bytesOnDisk.toString())
                    .field("error.type", "MissingPackageJson")
                    .log();
                return List.of();
            }
            return this.coordinatesFrom(manifest);
        } catch (final Exception ex) {
            EcsLogger.warn("com.auto1.pantera.prefetch")
                .message("npm package parse failed: " + ex.getMessage())
                .eventCategory("file")
                .eventAction("parse")
                .eventOutcome("failure")
                .field("file.path", bytesOnDisk.toString())
                .field("error.type", ex.getClass().getName())
                .log();
            return List.of();
        }
    }

    /**
     * Walk the tar stream until {@code package/package.json} is found and
     * return its bytes; returns {@code null} if the manifest is absent.
     */
    private static byte[] readManifest(final TarArchiveInputStream tar) throws java.io.IOException {
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            if (!entry.isDirectory() && MANIFEST_ENTRY.equals(entry.getName())) {
                return tar.readAllBytes();
            }
        }
        return null;
    }

    /**
     * Parse the manifest bytes and resolve each runtime dependency through
     * the {@link NpmMetadataLookup}; unresolved entries are dropped.
     */
    private List<Coordinate> coordinatesFrom(final byte[] manifest) throws java.io.IOException {
        final JsonNode root = JSON.readTree(manifest);
        final JsonNode deps = root.get("dependencies");
        if (deps == null || !deps.isObject()) {
            return List.of();
        }
        final List<Coordinate> result = new ArrayList<>();
        final Iterator<Map.Entry<String, JsonNode>> fields = deps.fields();
        while (fields.hasNext()) {
            final Map.Entry<String, JsonNode> field = fields.next();
            final String name = field.getKey();
            final JsonNode rangeNode = field.getValue();
            if (rangeNode == null || !rangeNode.isTextual()) {
                continue;
            }
            final String range = rangeNode.asText();
            final Optional<String> resolved = this.lookup.resolve(name, range);
            if (resolved.isPresent()) {
                result.add(Coordinate.npm(name, resolved.get()));
            }
        }
        return result;
    }
}
