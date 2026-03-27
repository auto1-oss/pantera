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

import com.auto1.pantera.docker.error.InvalidTagNameException;

import java.util.regex.Pattern;

public class ImageTag {

    /**
     * RegEx tag validation pattern.
     */
    private static final Pattern PATTERN =
        Pattern.compile("^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$");

    /**
     * Valid tag name.
     * Validation rules are the following:
     * <p>
     * A tag name must be valid ASCII and may contain
     * lowercase and uppercase letters, digits, underscores, periods and dashes.
     * A tag name may not start with a period or a dash and may contain a maximum of 128 characters.
     */
    public static String validate(String tag) {
        if (!valid(tag)) {
            throw new InvalidTagNameException(
                String.format("Invalid tag: '%s'", tag)
            );
        }
        return tag;
    }

    public static boolean valid(String tag) {
        return PATTERN.matcher(tag).matches();
    }
}
