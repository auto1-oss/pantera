/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
