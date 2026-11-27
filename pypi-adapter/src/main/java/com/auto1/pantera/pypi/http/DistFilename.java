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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.pypi.NormalizedProjectName;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project and release version of a PyPI distribution file, read from its
 * name.
 *
 * <p>Hosted files live at {@code <name>/<version>/<file>}, so the version
 * directory is authoritative there ({@link #versionOf(Key)}). Otherwise the
 * name of each kind of file PyPI has served says it:</p>
 * <ul>
 *   <li>source archives {@code name-ver.tar.gz} (and {@code .tgz},
 *     {@code .tar.bz2}, {@code .tbz}, {@code .tar.xz}, {@code .txz},
 *     {@code .tar.Z}, {@code .tar}, {@code .zip});</li>
 *   <li>wheels, eggs and disk images {@code name-ver-tags.whl|egg|dmg};</li>
 *   <li>Windows installers {@code name-ver.platform[-pyX.Y].exe|msi};</li>
 *   <li>RPM packages {@code name-ver-release.arch.rpm};</li>
 *   <li>Debian packages {@code name_ver[-revision]_arch.deb}.</li>
 * </ul>
 *
 * @since 2.2.9
 */
public final class DistFilename {

    /**
     * Source-distribution archive suffixes, lower case.
     */
    private static final List<String> SDIST_SUFFIXES = List.of(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.z", ".tgz", ".tbz", ".txz", ".zip", ".tar"
    );

    /**
     * Suffixes of built distributions named {@code name-ver-tags}.
     */
    private static final List<String> TAGGED_SUFFIXES = List.of(".whl", ".egg", ".dmg");

    /**
     * Windows installer of {@code bdist_wininst} / {@code bdist_msi}: the
     * {@code name-ver} stem, the platform, the optional target Python.
     */
    private static final Pattern INSTALLER = Pattern.compile(
        "(?<stem>.+)\\.(?:win32|win-[a-z0-9_]+|linux-[a-z0-9_]+|macosx-[0-9.]+-[a-z0-9_]+)"
            + "(?:-py[0-9.]+)?\\.(?:exe|msi)",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * RPM package of {@code bdist_rpm}: the {@code name-ver} stem, the
     * release, the architecture.
     */
    private static final Pattern RPM = Pattern.compile(
        "(?<stem>.+)-[^-]+\\.[a-z0-9_]+\\.rpm", Pattern.CASE_INSENSITIVE
    );

    /**
     * Debian package: name, upstream version, optional revision, architecture.
     */
    private static final Pattern DEB = Pattern.compile(
        "(?<name>[^_]+)_(?<version>[^_]+?)(?:-[^-_]+)?_[^_]+\\.deb", Pattern.CASE_INSENSITIVE
    );

    /**
     * A valid (not yet normalized) Python project name.
     */
    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9.\\-_]+");

    /**
     * Filename.
     */
    private final String name;

    /**
     * Ctor.
     * @param name Distribution filename
     */
    public DistFilename(final String name) {
        this.name = name;
    }

    /**
     * Version of the distribution stored at {@code key}: the version
     * directory for {@code <name>/<version>/<file>}, else the filename.
     * @param key Storage key relative to the repository root
     * @return Version, empty when it cannot be determined
     */
    static Optional<String> versionOf(final Key key) {
        final String[] parts = key.string().split("/");
        final Optional<String> result;
        if (parts.length >= 3) {
            result = Optional.of(parts[parts.length - 2]);
        } else {
            result = new DistFilename(parts[parts.length - 1]).version();
        }
        return result;
    }

    /**
     * Version encoded in the filename.
     * @return Version, empty when the name has no recognised shape
     */
    public Optional<String> version() {
        return this.parts().map(Parts::version).filter(version -> !version.isEmpty());
    }

    /**
     * Normalized project name encoded in the filename.
     * @return Project name, empty when the name has no recognised shape
     */
    public Optional<String> project() {
        return this.parts().map(Parts::project)
            .filter(raw -> DistFilename.PROJECT.matcher(raw).matches())
            .map(raw -> new NormalizedProjectName.Simple(raw).value());
    }

    /**
     * Project and version as they are written in the filename.
     * @return Parts, empty when the name has no recognised shape
     */
    private Optional<Parts> parts() {
        final String lower = this.name.toLowerCase(Locale.ROOT);
        final String bare = this.name.substring(0, Math.max(0, this.name.length() - 4));
        final Optional<Parts> result;
        if (DistFilename.TAGGED_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            result = DistFilename.tagged(bare);
        } else if (lower.endsWith(".exe") || lower.endsWith(".msi")) {
            result = this.matched(DistFilename.INSTALLER)
                .flatMap(DistFilename::stem)
                .or(() -> DistFilename.tagged(bare));
        } else if (lower.endsWith(".rpm")) {
            result = this.matched(DistFilename.RPM)
                .flatMap(DistFilename::stem)
                .or(() -> DistFilename.lastDash(bare));
        } else if (lower.endsWith(".deb")) {
            result = this.matched(DistFilename.DEB)
                .map(deb -> new Parts(deb.group("name"), deb.group("version")))
                .or(() -> DistFilename.lastDash(bare));
        } else {
            result = DistFilename.lastDash(this.stem(lower));
        }
        return result;
    }

    /**
     * Match the whole filename.
     * @param pattern Pattern of a kind of file
     * @return Matcher, empty when the name does not match
     */
    private Optional<Matcher> matched(final Pattern pattern) {
        final Matcher matcher = pattern.matcher(this.name);
        final Optional<Matcher> result;
        if (matcher.matches()) {
            result = Optional.of(matcher);
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Filename without its source-distribution archive suffix.
     * @param lower Lower-cased filename
     * @return Stem
     */
    private String stem(final String lower) {
        String stem = this.name;
        for (final String suffix : DistFilename.SDIST_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                stem = this.name.substring(0, this.name.length() - suffix.length());
                break;
            }
        }
        return stem;
    }

    /**
     * Parts of the {@code name-ver} stem a pattern found.
     * @param matcher Matcher with a {@code stem} group
     * @return Parts
     */
    private static Optional<Parts> stem(final Matcher matcher) {
        return DistFilename.lastDash(matcher.group("stem"));
    }

    /**
     * Parts of {@code name-ver-tags}: the name before the first dash, the
     * version after it. Wheels escape dashes in both.
     * @param bare Filename without its suffix
     * @return Parts, empty without a version
     */
    private static Optional<Parts> tagged(final String bare) {
        final String[] parts = bare.split("-");
        final Optional<Parts> result;
        if (parts.length >= 2) {
            result = Optional.of(new Parts(parts[0], parts[1]));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Parts of {@code name-ver}: the version after the last dash, since an
     * old project name may contain dashes.
     * @param stem Filename without its suffix
     * @return Parts, empty without a dash
     */
    private static Optional<Parts> lastDash(final String stem) {
        final int dash = stem.lastIndexOf('-');
        final Optional<Parts> result;
        if (dash > 0) {
            result = Optional.of(new Parts(stem.substring(0, dash), stem.substring(dash + 1)));
        } else {
            result = Optional.empty();
        }
        return result;
    }

    /**
     * Project and version as written in a filename.
     * @param project Project name, not normalized
     * @param version Version
     */
    private record Parts(String project, String version) {
    }
}
