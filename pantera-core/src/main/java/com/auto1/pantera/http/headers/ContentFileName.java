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
package com.auto1.pantera.http.headers;

import java.net.URI;
import java.nio.file.Paths;

/**
 * Content-Disposition header for a file.
 */
public final class ContentFileName extends Header {
    /**
     * Ctor.
     *
     * @param filename Name of attachment file.
     */
    public ContentFileName(final String filename) {
        super(
            new ContentDisposition(
                String.format("attachment; filename=\"%s\"", filename)
            )
        );
    }

    /**
     * Ctor.
     *
     * @param uri Requested URI.
     */
    public ContentFileName(final URI uri) {
        this(Paths.get(uri.getPath()).getFileName().toString());
    }
}
