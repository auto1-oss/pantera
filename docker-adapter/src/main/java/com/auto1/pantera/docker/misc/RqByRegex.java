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
package com.auto1.pantera.docker.misc;

import com.auto1.pantera.http.rq.RequestLine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request by RegEx pattern.
 */
public final class RqByRegex {

    private final Matcher path;

    public RqByRegex(RequestLine line, Pattern regex) {
        String path = line.uri().getPath();
        Matcher matcher = regex.matcher(path);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unexpected path: " + path);
        }
        this.path = matcher;
    }

    /**
     * Matches request path by RegEx pattern.
     *
     * @return Path matcher.
     */
    public Matcher path() {
        return path;
    }
}
