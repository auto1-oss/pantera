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
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Parses an npm packument JSON file (the {@code <pkgname>/meta.json} document)
 * and emits {@link Coordinate#npmPackument(String)} entries for the package's
 * direct runtime dependencies (Phase 13 speculative packument prefetch).
 *
 * <p>The packument waterfall is the dominant cold-cache npm bottleneck
 * (Phase 12.5 profile: 8.45s of 12.9s wall = 65%). {@code npm install}
 * fetches each dep's packument serially before fetching tarballs; the
 * existing tarball prefetch fires too late to warm the metadata path. This
 * parser identifies the direct deps from the packument and lets the
 * coordinator dispatch packument GETs for them ahead of npm install's
 * walk.</p>
 *
 * <h2>Version selection</h2>
 * <p>A packument lists ALL versions of a package. We pick the version
 * {@code dist-tags.latest} points at and read its {@code dependencies}
 * map. We do NOT resolve range expressions for the deps — we only need
 * the dep's NAME to fire the next packument GET (the upstream URL is
 * version-less for packuments). This keeps the parser fast and
 * upstream-independent.</p>
 *
 * <h2>What we emit</h2>
 * <p>Each dep entry yields a {@link Coordinate#npmPackument(String)}
 * (with empty version) — the coordinator's URL builder then resolves
 * to {@code <upstream>/<scope>/<name>}. This cascades through the
 * dep tree: each warmed packument fires its own packument-write event,
 * which re-enters this parser, which warms ITS deps, etc. The
 * coordinator's dedup set + bounded queue + circuit breaker bound
 * the explosion.</p>
 *
 * <h2>Failure containment</h2>
 * <p>On any I/O / parse / shape failure (missing file, malformed JSON,
 * no {@code dist-tags}, no {@code versions[<latest>]}, etc.) the parser
 * logs a WARN with {@code error.type} and returns an empty list — it
 * never throws.</p>
 *
 * @since 2.2.0
 */
public final class NpmPackumentParser implements PrefetchParser {

    /** Shared, thread-safe JSON reader. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Logger name used for diagnostic events.
     */
    private static final String LOGGER = "com.auto1.pantera.prefetch.NpmPackumentParser";

    /**
     * Build an instance. The parser is stateless and thread-safe.
     */
    public NpmPackumentParser() {
        // no state
    }

    @Override
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public List<Coordinate> parse(final Path bytesOnDisk) {
        try {
            final JsonNode root = JSON.readTree(bytesOnDisk.toFile());
            final JsonNode versions = root.get("versions");
            if (versions == null || !versions.isObject()) {
                return List.of();
            }
            final String latest = pickLatest(root, versions);
            if (latest == null) {
                return List.of();
            }
            final JsonNode versionEntry = versions.get(latest);
            if (versionEntry == null || !versionEntry.isObject()) {
                return List.of();
            }
            final JsonNode deps = versionEntry.get("dependencies");
            if (deps == null || !deps.isObject()) {
                return List.of();
            }
            final List<Coordinate> result = new ArrayList<>();
            final Iterator<String> names = deps.fieldNames();
            while (names.hasNext()) {
                final String depName = names.next();
                if (depName == null || depName.isEmpty()) {
                    continue;
                }
                result.add(Coordinate.npmPackument(depName));
            }
            return result;
        } catch (final Exception ex) {
            EcsLogger.warn(LOGGER)
                .message("npm packument parse failed: " + ex.getMessage())
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
     * Pick the version whose {@code dependencies} we'll prefetch.
     * Preference: {@code dist-tags.latest} when present and exists in
     * {@code versions}; otherwise the highest stable semver in
     * {@code versions}; otherwise null.
     */
    private static String pickLatest(final JsonNode root, final JsonNode versions) {
        final JsonNode distTags = root.get("dist-tags");
        if (distTags != null && distTags.isObject()) {
            final JsonNode latestNode = distTags.get("latest");
            if (latestNode != null && latestNode.isTextual()) {
                final String latest = latestNode.asText();
                if (versions.has(latest)) {
                    return latest;
                }
            }
        }
        // Fall back: pick the highest version we can parse. Filter out
        // prereleases — npm's `latest` is by convention a stable release.
        Semver bestSem = null;
        String bestStr = null;
        final Iterator<String> names = versions.fieldNames();
        while (names.hasNext()) {
            final String candidate = names.next();
            final Semver sem;
            try {
                sem = new Semver(candidate, Semver.SemverType.NPM);
            } catch (final SemverException ex) {
                continue;
            }
            final String[] suffixes = sem.getSuffixTokens();
            if (suffixes != null && suffixes.length > 0) {
                continue;
            }
            if (bestSem == null || sem.isGreaterThan(bestSem)) {
                bestSem = sem;
                bestStr = candidate;
            }
        }
        return bestStr;
    }
}
