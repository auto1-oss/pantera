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
package com.auto1.pantera.http.html;

/**
 * The one HTML-escaping helper for every untrusted value Pantera renders
 * into markup — artifact/blob names in directory listings, PyPI yank
 * reasons and index attributes, anything that originates from an upload
 * or an upstream. Escapes the five characters that can break out of text
 * or a double/single-quoted attribute.
 *
 * <p>Before 2.2.9 the Files listing interpolated blob keys raw into
 * {@code href} and text, and the PyPI simple index emitted the caller's
 * yank reason verbatim into {@code data-yanked}, so a writer could store a
 * payload that rendered as markup for every later reader.</p>
 *
 * @since 2.2.9
 */
public final class HtmlEscape {

    /**
     * Not instantiable.
     */
    private HtmlEscape() {
    }

    /**
     * Escape a value for safe inclusion in HTML text or a quoted attribute.
     *
     * @param value Untrusted value (nullable → empty)
     * @return Escaped value
     */
    public static String escape(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(value.length() + 16);
        for (int idx = 0; idx < value.length(); idx = idx + 1) {
            final char chr = value.charAt(idx);
            switch (chr) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(chr);
                    break;
            }
        }
        return out.toString();
    }
}
