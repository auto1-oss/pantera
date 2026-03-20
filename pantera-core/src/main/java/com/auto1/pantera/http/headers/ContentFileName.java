/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
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
