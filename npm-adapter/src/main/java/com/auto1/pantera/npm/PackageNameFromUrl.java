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
package com.auto1.pantera.npm;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.regex.Pattern;

/**
 * Get package name (can be scoped) from request url.
 * @since 0.6
 */
public class PackageNameFromUrl {

    /**
     * Request url.
     */
    private final RequestLine url;

    public PackageNameFromUrl(String url) {
        this.url = RequestLine.from(url);
    }

    /**
     * @param url Request url
     */
    public PackageNameFromUrl(RequestLine url) {
        this.url = url;
    }

    /**
     * Gets package name from url.
     * @return Package name
     */
    public String value() {
        final String abspath = this.url.uri().getPath();
        final String context = "/";
        if (abspath.startsWith(context)) {
            return abspath.replaceFirst(
                String.format("%s/?", Pattern.quote(context)),
                ""
            );
        } else {
            throw new PanteraException(
                new IllegalArgumentException(
                    String.format(
                        "Path is expected to start with '%s' but was '%s'",
                        context,
                        abspath
                    )
                )
            );
        }
    }
}
