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
package com.auto1.pantera.pypi.meta;

import com.auto1.pantera.pypi.NormalizedProjectName;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Python package valid filename.
 * @since 0.6
 */
public final class ValidFilename {

    /**
     * Pattern to obtain package name from uploaded file name: for file name
     * 'Pantera-Testpkg-0.0.3.tar.gz', then package name is 'Pantera-Testpkg'.
     */
    private static final Pattern ARCHIVE_PTRN =
        Pattern.compile("(?<name>.*)-(?<version>[0-9a-z.]+?)\\.([a-zA-Z.]+)");

    /**
     * Python wheel package name pattern, for more details see
     * <a href="https://www.python.org/dev/peps/pep-0427/#file-name-convention">docs</a>.
     */
    private static final Pattern WHEEL_PTRN =
        Pattern.compile("(?<name>.*?)-(?<version>[0-9a-z.]+)(-\\d+)?-((py\\d.?)+)-(.*)-(.*).whl");

    /**
     * Package info data.
     */
    private final PackageInfo data;

    /**
     * Filename.
     */
    private final String filename;

    /**
     * Ctor.
     * @param data Package info data
     * @param filename Filename
     */
    public ValidFilename(final PackageInfo data, final String filename) {
        this.data = data;
        this.filename = filename;
    }

    /**
     * Is filename valid?
     * @return True if filename corresponds to project metadata, false - otherwise.
     */
    public boolean valid() {
        return Stream.of(
            ValidFilename.WHEEL_PTRN.matcher(this.filename),
            ValidFilename.ARCHIVE_PTRN.matcher(this.filename)
        ).filter(Matcher::matches).findFirst().map(
            matcher -> {
                final String name = new NormalizedProjectName.Simple(this.data.name()).value();
                return name.equals(new NormalizedProjectName.Simple(matcher.group("name")).value())
                    && this.data.version().equals(matcher.group("version"));
            }
        ).orElse(false);
    }

}
