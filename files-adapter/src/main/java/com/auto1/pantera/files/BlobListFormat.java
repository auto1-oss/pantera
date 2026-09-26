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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Key;
import com.auto1.pantera.http.html.HtmlEscape;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.json.Json;

/**
 * Format of a blob list.
 *
 * @since 0.8
 */
interface BlobListFormat {

    /**
     * Stamdard format implementations.
     * @since 1.0
     */
    enum Standard implements BlobListFormat {

        /**
         * Text format renders keys as a list of strings
         * separated by newline char {@code \n}.
         */
        TEXT(
            (keys, base) -> keys.stream().map(Key::string).collect(Collectors.joining("\n"))
        ),

        /**
         * Json format renders keys as JSON array with
         * keys items.
         */
        JSON(
            (keys, base) -> Json.createArrayBuilder(
                keys.stream().map(Key::string).collect(Collectors.toList())
            ).build().toString()
        ),

        /**
         * HTML format renders keys as simple markdown with ul, li and a tags.
         * A link is the link base followed by the percent-encoded key; link
         * and text are HTML-escaped.
         */
        HTML(
            (keys, base) -> String.format(
                String.join(
                    "\n",
                    "<!DOCTYPE html>",
                    "<html>",
                    "  <head><meta charset=\"utf-8\"/></head>",
                    "  <body>",
                    "    <ul>",
                    "%s",
                    "    </ul>",
                    "  </body>",
                    "</html>"
                ),
                keys.stream().map(
                    // SECURITY (2.2.9): the key is writer-controlled; escape it
                    // for both the attribute and the text position.
                    key -> String.format(
                        "      <li><a href=\"%s\">%s</a></li>",
                        HtmlEscape.escape(base + Standard.encode(key.string())),
                        HtmlEscape.escape(key.string())
                    )
                ).collect(Collectors.joining("\n"))
            )
        );

        /**
         * Format.
         */
        private final BiFunction<Collection<? extends Key>, String, String> fmt;

        /**
         * Enum instance.
         * @param fmt Format of the keys and the link base
         */
        Standard(final BiFunction<Collection<? extends Key>, String, String> fmt) {
            this.fmt = fmt;
        }

        @Override
        public String apply(final Collection<? extends Key> blobs, final String base) {
            return this.fmt.apply(blobs, base);
        }

        /**
         * Percent-encode every segment of a key path.
         * @param path Key path
         * @return URL path
         */
        private static String encode(final String path) {
            return Arrays.stream(path.split("/", -1))
                .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
        }
    }

    /**
     * Apply the format to the list of blobs, linking from the root.
     * @param blobs List of blobs
     * @return Text formatted
     */
    default String apply(final Collection<? extends Key> blobs) {
        return this.apply(blobs, "/");
    }

    /**
     * Apply the format to the list of blobs.
     * @param blobs List of blobs
     * @param base Link base the keys are appended to, ending with {@code /}
     * @return Text formatted
     */
    String apply(Collection<? extends Key> blobs, String base);
}
