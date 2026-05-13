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
package com.auto1.pantera;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Predicate to test whether request path matches some regex and corresponds to some conditions.
 * @since 0.23
 */
public enum RqPath implements Predicate<String> {

    /**
     * Anaconda client performs requests of the following formats:
     * <code>/t/ol-4ee312d8-9fe2-44d2-bea9-053325e1ffd5/my-conda/noarch/repodata.json</code>
     * Where second part is user token, third is repository name,
     * then follows conda repository architecture (for example: noarch, linux-64, win-64 etc).
     * This {@link Predicate} implementation tests whether path is such conda path.
     */
    CONDA(Pattern.compile("/t/.*(repodata\\.json|\\.conda|\\.tar\\.bz2)")) {

        @Override
        public boolean test(final String path) {
            final int length = path.split("/").length;
            return CONDA.ptrn.matcher(path).matches() && (length == 6 || length == 7);
        }
    };

    /**
     * Path pattern.
     */
    private final Pattern ptrn;

    /**
     * Ctor.
     * @param ptrn Path pattern
     */
    RqPath(final Pattern ptrn) {
        this.ptrn = ptrn;
    }

}
