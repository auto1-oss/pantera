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
package com.auto1.pantera.backfill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scanner for Conda repositories.
 *
 * <p>Conda's indexed {@code name} column is a synthetic
 * {@code "<name>_<arch>"} composite, unrelated to the real storage key
 * {@code "<arch-dir>/<filename>"} — reconstructing that key from the DB's
 * name/version columns alone is not reliable (the build string in the
 * filename, e.g. {@code py39_0}, is not recoverable from them). Rather
 * than guess, this scanner reads each arch directory's own
 * {@code repodata.json} — the same authoritative per-package metadata
 * (name, version, arch) that {@code UpdateSlice} itself wrote at upload
 * time — so name/version reconstruction is exact, never a guess, and the
 * real key is simply the path already being walked.</p>
 *
 * @since 1.20.13
 */
final class CondaScanner implements Scanner {

    /**
     * Logger.
     */
    private static final Logger LOG =
        LoggerFactory.getLogger(CondaScanner.class);

    /**
     * Repodata index filename, present in every arch directory.
     */
    private static final String REPODATA = "repodata.json";

    /**
     * Repodata section for {@code .tar.bz2} packages.
     */
    private static final String PACKAGES = "packages";

    /**
     * Repodata section for {@code .conda} packages.
     */
    private static final String PACKAGES_CONDA = "packages.conda";

    /**
     * Fallback package name, mirrors {@code UpdateSlice}'s default.
     */
    private static final String NO_NAME = "<no name>";

    /**
     * Fallback arch, mirrors {@code UpdateSlice}'s default.
     */
    private static final String NO_ARCH = "<no arch>";

    @Override
    public Stream<ArtifactRecord> scan(final Path root, final String repoName)
        throws IOException {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        final List<Path> archDirs;
        try (Stream<Path> children = Files.list(root)) {
            archDirs = children.filter(Files::isDirectory)
                .collect(Collectors.toList());
        }
        final List<ArtifactRecord> records = new ArrayList<>();
        for (final Path archDir : archDirs) {
            records.addAll(this.scanArchDir(root, archDir, repoName));
        }
        return records.stream();
    }

    /**
     * Scan a single arch directory's {@code repodata.json} for package
     * entries.
     *
     * @param root Repository root
     * @param archDir Arch subdirectory (e.g. {@code linux-64})
     * @param repoName Logical repository name
     * @return Artifact records found in this arch directory's repodata
     */
    private List<ArtifactRecord> scanArchDir(final Path root,
        final Path archDir, final String repoName) {
        final Path repodata = archDir.resolve(CondaScanner.REPODATA);
        final List<ArtifactRecord> records = new ArrayList<>();
        if (!Files.isRegularFile(repodata)) {
            return records;
        }
        final JsonObject index;
        try (
            InputStream input = Files.newInputStream(repodata);
            JsonReader reader = Json.createReader(input)
        ) {
            index = reader.readObject();
        } catch (final IOException | JsonException ex) {
            LOG.warn(
                "Cannot read {}: {} — skipping this arch directory",
                repodata, ex.getMessage()
            );
            return records;
        }
        final String archPrefix = root.relativize(archDir).toString()
            .replace('\\', '/');
        this.addSection(index, CondaScanner.PACKAGES, archDir, archPrefix, repoName, records);
        this.addSection(
            index, CondaScanner.PACKAGES_CONDA, archDir, archPrefix, repoName, records
        );
        return records;
    }

    /**
     * Convert one repodata.json section ({@code packages} or
     * {@code packages.conda}) into artifact records.
     *
     * @param index Parsed repodata.json
     * @param section Section name to read
     * @param archDir Arch subdirectory, used to stat each package file
     * @param archPrefix Repo-relative arch directory, e.g. {@code linux-64}
     * @param repoName Logical repository name
     * @param records Accumulator for produced records
     */
    private void addSection(final JsonObject index, final String section,
        final Path archDir, final String archPrefix, final String repoName,
        final List<ArtifactRecord> records) {
        if (!index.containsKey(section)) {
            return;
        }
        final JsonObject packages = index.getJsonObject(section);
        for (final String filename : packages.keySet()) {
            records.add(
                CondaScanner.toRecord(
                    packages.getJsonObject(filename), archDir, archPrefix, filename, repoName
                )
            );
        }
    }

    /**
     * Build an artifact record from one repodata.json package entry. Reads
     * name/version/arch straight from the entry — the same metadata
     * {@code UpdateSlice} wrote there at upload time — so the reconstructed
     * {@code "<name>_<arch>"} composite matches the existing indexed row
     * exactly.
     *
     * @param meta Package metadata object for this filename
     * @param archDir Arch subdirectory, used to stat the package file
     * @param archPrefix Repo-relative arch directory
     * @param filename Package filename (repodata.json key)
     * @param repoName Logical repository name
     * @return Artifact record for this package
     */
    private static ArtifactRecord toRecord(final JsonObject meta,
        final Path archDir, final String archPrefix, final String filename,
        final String repoName) {
        final String pkgName = meta.getString("name", CondaScanner.NO_NAME);
        final String arch = meta.getString("arch", CondaScanner.NO_ARCH);
        final String version = meta.getString("version", "");
        final long size = meta.containsKey("size")
            ? meta.getJsonNumber("size").longValue() : 0L;
        return new ArtifactRecord(
            "conda",
            repoName,
            String.join("_", pkgName, arch),
            version,
            size,
            CondaScanner.mtimeOf(archDir.resolve(filename)),
            null,
            "system",
            archPrefix + "/" + filename
        );
    }

    /**
     * Last-modified time of a package file, in epoch millis. Falls back to
     * the current time when the file is missing or unreadable (e.g. a
     * repodata.json entry surviving a since-deleted package) so a single
     * stale entry never aborts the whole scan.
     *
     * @param path Package file path
     * @return Last-modified time in epoch millis
     */
    private static long mtimeOf(final Path path) {
        long result;
        try {
            result = Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException ex) {
            result = System.currentTimeMillis();
        }
        return result;
    }
}
