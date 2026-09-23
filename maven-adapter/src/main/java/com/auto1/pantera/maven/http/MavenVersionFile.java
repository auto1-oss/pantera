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
package com.auto1.pantera.maven.http;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file inside a Maven version directory,
 * {@code <group path>/<artifactId>/<versionDir>/<name>}, and the cooldown
 * (artifact, version) key it is gated under.
 *
 * <p>Deliberately independent of {@link MavenSlice#EXT} /
 * {@link MavenSlice#ARTIFACT}, which drive routing and event indexing: every
 * primary file of a version — jar, pom, Gradle {@code .module}, war, aar,
 * classifier jars incl. {@code -sources} / {@code -javadoc} — maps to the
 * SAME key as the main jar, so one block covers the whole version and one
 * unblock releases it. Checksum / signature sidecars have no key: they
 * follow their primary.</p>
 *
 * @since 2.2.9
 */
final class MavenVersionFile {

    /**
     * Extensions of the cooldown-gated primary files of a version: the
     * {@link MavenSlice#EXT} set plus Gradle's {@code module}.
     */
    private static final List<String> GATED = List.of(
        "jar", "war", "maven-plugin", "ejb", "ear", "rar", "zip", "aar", "pom", "module"
    );

    /**
     * SNAPSHOT version directory suffix.
     */
    private static final String SNAPSHOT = "-SNAPSHOT";

    /**
     * Timestamp part of a SNAPSHOT file name after {@code <artifactId>-<base>-}:
     * {@code yyyyMMdd.HHmmss-N}, then a classifier ({@code -...}) or the
     * extension ({@code .}).
     */
    private static final Pattern STAMP = Pattern.compile("^(\\d{8}\\.\\d{6}-\\d+)[-.].*$");

    /**
     * {@code <group path>/<artifactId>}.
     */
    private final String groupArtifact;

    /**
     * Artifact id (the version directory's parent).
     */
    private final String artifactId;

    /**
     * Version directory name.
     */
    private final String versionDir;

    /**
     * File name.
     */
    private final String name;

    /**
     * Ctor.
     *
     * @param groupArtifact Group path + artifactId
     * @param artifactId Artifact id
     * @param versionDir Version directory
     * @param name File name
     */
    private MavenVersionFile(
        final String groupArtifact, final String artifactId,
        final String versionDir, final String name
    ) {
        this.groupArtifact = groupArtifact;
        this.artifactId = artifactId;
        this.versionDir = versionDir;
        this.name = name;
    }

    /**
     * Split a request path into its version-file parts.
     *
     * @param path Request path (leading slash optional)
     * @return Parts, or empty when the path is not
     *  {@code group/artifactId/version/file}
     */
    static Optional<MavenVersionFile> parse(final String path) {
        final String key = path.startsWith("/") ? path.substring(1) : path;
        final int file = key.lastIndexOf('/');
        final int ver = file > 0 ? key.lastIndexOf('/', file - 1) : -1;
        final int art = ver > 0 ? key.lastIndexOf('/', ver - 1) : -1;
        if (art <= 0 || file == key.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(
            new MavenVersionFile(
                key.substring(0, ver), key.substring(art + 1, ver),
                key.substring(ver + 1, file), key.substring(file + 1)
            )
        );
    }

    /**
     * Whether this is a cooldown-gated primary file (by extension; sidecars
     * such as {@code .sha1} / {@code .asc} never are).
     *
     * @return True when gated
     */
    boolean gated() {
        final int dot = this.name.lastIndexOf('.');
        return dot > 0
            && GATED.contains(this.name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Dotted cooldown artifact name, e.g. {@code com.example.my-lib}.
     *
     * @return Artifact name
     */
    String artifact() {
        return MavenSlice.EVENT_INFO.formatArtifactName(this.groupArtifact);
    }

    /**
     * Cooldown version: the timestamped form for a timestamped SNAPSHOT
     * upload (so DB rows and gates differentiate uploads), otherwise the
     * version directory (releases and {@code my-lib-1.0-SNAPSHOT.jar}).
     *
     * @return Version
     */
    String version() {
        return this.snapshotTimestampVersion().orElse(this.versionDir);
    }

    /**
     * Timestamped version of a SNAPSHOT upload named
     * {@code <artifactId>-<base>-<yyyyMMdd.HHmmss-N>[-classifier].<ext>}.
     * The base version comes from the directory ({@code 1.0-SNAPSHOT}
     * &rarr; {@code 1.0}) and the artifactId from the parent directory, so
     * hyphens in either never mis-split the file name.
     *
     * @return {@code <base>-<yyyyMMdd.HHmmss-N>}, or empty
     */
    Optional<String> snapshotTimestampVersion() {
        if (!this.versionDir.endsWith(SNAPSHOT)) {
            return Optional.empty();
        }
        final String base = this.versionDir.substring(
            0, this.versionDir.length() - SNAPSHOT.length()
        );
        final String prefix = this.artifactId + "-" + base + "-";
        if (base.isEmpty() || !this.name.startsWith(prefix)) {
            return Optional.empty();
        }
        final Matcher stamp = STAMP.matcher(this.name.substring(prefix.length()));
        if (!stamp.matches()) {
            return Optional.empty();
        }
        return Optional.of(base + "-" + stamp.group(1));
    }
}
