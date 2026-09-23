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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * Content-Disposition header for a file, per RFC 6266: an ASCII
 * {@code filename} with quotes, backslashes, control and non-ASCII
 * characters replaced by {@code _}, plus an RFC 5987 {@code filename*}
 * carrying the exact UTF-8 name whenever the two differ. The name can
 * therefore never close the quoted string and inject parameters.
 */
public final class ContentFileName extends Header {

    /**
     * RFC 5987 attr-char punctuation left unencoded in {@code filename*}.
     */
    private static final String ATTR_CHARS = "!#$&+-.^_`|~";

    /**
     * Hex digits for percent-encoding.
     */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * Ctor.
     *
     * @param filename Name of attachment file.
     */
    public ContentFileName(final String filename) {
        super(new ContentDisposition(ContentFileName.value(filename)));
    }

    /**
     * Ctor.
     *
     * @param uri Requested URI.
     */
    public ContentFileName(final URI uri) {
        this(Paths.get(uri.getPath()).getFileName().toString());
    }

    /**
     * Header value for a file name.
     * @param filename File name
     * @return Content-Disposition value
     */
    private static String value(final String filename) {
        final String ascii = ContentFileName.ascii(filename);
        final String plain = "attachment; filename=\"" + ascii + '"';
        final String res;
        if (ascii.equals(filename)) {
            res = plain;
        } else {
            res = plain + "; filename*=UTF-8''" + ContentFileName.encoded(filename);
        }
        return res;
    }

    /**
     * Safe quoted-string content: printable ASCII except quote and backslash.
     * @param filename File name
     * @return ASCII fallback name
     */
    private static String ascii(final String filename) {
        final StringBuilder res = new StringBuilder(filename.length());
        filename.codePoints().forEach(
            cp -> {
                if (cp < 0x20 || cp > 0x7e || cp == '"' || cp == '\\') {
                    res.append('_');
                } else {
                    res.append((char) cp);
                }
            }
        );
        return res.toString();
    }

    /**
     * RFC 5987 value-chars: UTF-8 bytes, attr-chars kept, the rest %XX.
     * @param filename File name
     * @return Percent-encoded name
     */
    private static String encoded(final String filename) {
        final StringBuilder res = new StringBuilder();
        for (final byte octet : filename.getBytes(StandardCharsets.UTF_8)) {
            final int chr = octet & 0xff;
            if (chr >= 'A' && chr <= 'Z' || chr >= 'a' && chr <= 'z' || chr >= '0' && chr <= '9'
                || ContentFileName.ATTR_CHARS.indexOf(chr) >= 0) {
                res.append((char) chr);
            } else {
                res.append('%').append(ContentFileName.HEX[chr >> 4])
                    .append(ContentFileName.HEX[chr & 0xf]);
            }
        }
        return res.toString();
    }
}
