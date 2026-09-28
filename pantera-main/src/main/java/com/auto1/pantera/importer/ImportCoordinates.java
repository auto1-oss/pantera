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
package com.auto1.pantera.importer;

import com.auto1.pantera.goproxy.ModulePath;
import com.auto1.pantera.scheduling.RepositoryEvents;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Artifact coordinates of an imported file, derived from its repository path
 * the way a native publish of the same format records them, so imported and
 * natively published artifacts share one package name and version in the
 * artifact index, search and the audit trail (R41, R42).
 *
 * <ul>
 *   <li>maven / gradle: {@code groupId.artifactId} and the version directory,
 *   for primary artifact files only (checksums, signatures, sources/javadoc
 *   jars and {@code maven-metadata.xml} are companions and not recorded, as
 *   on a native deploy);</li>
 *   <li>file: the path with {@code /} replaced by {@code .}, and the version
 *   detected from it, {@code UNKNOWN} when none (as for a plain upload);</li>
 *   <li>npm: the package name and the tarball's version (tarballs only);</li>
 *   <li>go: the real module path and the version without {@code v}
 *   (module zips only);</li>
 *   <li>pypi: the PEP 503 normalized project name and the version directory;</li>
 *   <li>other formats: the path and {@code UNKNOWN}.</li>
 * </ul>
 *
 * @since 2.2.9
 */
final class ImportCoordinates {

    /**
     * Go module file in storage: {@code <module>/@v/v<version>.<ext>}.
     */
    private static final Pattern GO = Pattern.compile("^(.+)/@v/v([^/]+)\\.(zip|mod|info)$");

    /**
     * PEP 503 name separators.
     */
    private static final Pattern PYPI_SEPARATORS = Pattern.compile("[-_.]+");

    /**
     * Repository type.
     */
    private final String type;

    /**
     * Repository-relative path, no leading slash.
     */
    private final String path;

    /**
     * Ctor.
     * @param type Repository type
     * @param path Repository-relative artifact path
     */
    ImportCoordinates(final String type, final String path) {
        this.type = type == null ? "" : type.toLowerCase(Locale.ROOT);
        this.path = path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Coordinates to record for the file.
     * @return Coordinates, empty for a companion file a native publish does
     *  not record as an artifact
     */
    Optional<Coordinates> value() {
        return switch (this.type) {
            case "maven", "gradle" -> this.maven();
            case "file", "file-proxy" -> Optional.of(this.file());
            case "npm" -> this.npm();
            case "go" -> this.go();
            case "pypi", "python" -> Optional.of(this.pypi());
            default -> Optional.of(
                new Coordinates(this.path, RepositoryEvents.VERSION, this.path)
            );
        };
    }

    private Optional<Coordinates> maven() {
        final String[] segs = this.path.split("/");
        final Optional<Coordinates> result;
        if (segs.length < 4 || ImportCoordinates.mavenCompanion(segs[segs.length - 1])
            || !segs[segs.length - 1].startsWith(segs[segs.length - 3] + "-")) {
            result = Optional.empty();
        } else {
            final String dir = this.path.substring(0, this.path.lastIndexOf('/'));
            final String version = segs[segs.length - 2];
            result = Optional.of(
                new Coordinates(
                    dir.substring(0, dir.lastIndexOf('/')).replace('/', '.'), version, dir
                )
            );
        }
        return result;
    }

    private Coordinates file() {
        final String name = this.path.replace('/', '.');
        return new Coordinates(
            name, RepositoryEvents.detectFileVersion("file", name), this.path
        );
    }

    private Optional<Coordinates> npm() {
        final int sep = this.path.indexOf("/-/");
        final Optional<Coordinates> result;
        if (sep > 0 && this.path.endsWith(".tgz")) {
            final String name = this.path.substring(0, sep);
            final String file = this.path.substring(this.path.lastIndexOf('/') + 1);
            final String base = name.substring(name.lastIndexOf('/') + 1) + '-';
            final String version;
            if (file.startsWith(base) && file.length() > base.length() + ".tgz".length()) {
                version = file.substring(base.length(), file.length() - ".tgz".length());
            } else {
                version = RepositoryEvents.VERSION;
            }
            result = Optional.of(new Coordinates(name, version, this.path));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    private Optional<Coordinates> go() {
        final Matcher matcher = ImportCoordinates.GO.matcher(this.path);
        final Optional<Coordinates> result;
        if (matcher.matches() && "zip".equals(matcher.group(3))) {
            result = Optional.of(
                new Coordinates(
                    new ModulePath(matcher.group(1)).decoded(), matcher.group(2), this.path
                )
            );
        } else {
            result = Optional.empty();
        }
        return result;
    }

    private Coordinates pypi() {
        final String[] segs = this.path.split("/");
        final Coordinates result;
        if (segs.length >= 3) {
            result = new Coordinates(
                ImportCoordinates.PYPI_SEPARATORS.matcher(segs[0]).replaceAll("-")
                    .toLowerCase(Locale.ROOT),
                segs[1],
                this.path
            );
        } else {
            result = new Coordinates(this.path, RepositoryEvents.VERSION, this.path);
        }
        return result;
    }

    /**
     * Whether a Maven file is a companion of an artifact, not an artifact.
     * @param file File name
     * @return True for metadata, checksums, signatures, sources and javadoc
     */
    private static boolean mavenCompanion(final String file) {
        return file.startsWith("maven-metadata.xml")
            || file.endsWith(".md5") || file.endsWith(".sha1")
            || file.endsWith(".sha256") || file.endsWith(".sha512")
            || file.endsWith(".asc") || file.endsWith(".sig")
            || file.endsWith("-sources.jar") || file.endsWith("-javadoc.jar");
    }

    /**
     * Recorded coordinates.
     * @param name Package name
     * @param version Package version
     * @param prefix Browse path of the artifact
     */
    record Coordinates(String name, String version, String prefix) {
    }
}
