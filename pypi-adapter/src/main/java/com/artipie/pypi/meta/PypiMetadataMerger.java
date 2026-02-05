/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.meta;

import com.artipie.cache.MetadataMerger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PyPI metadata merger for group repositories.
 * Merges /simple/ HTML index pages from multiple PyPI repositories.
 *
 * <p>Merge rules:
 * <ul>
 *   <li>Parse HTML links with regex</li>
 *   <li>Deduplicate by filename (extracted from href)</li>
 *   <li>Sort alphabetically by filename</li>
 *   <li>Generate valid HTML output</li>
 * </ul>
 *
 * @since 1.18.0
 */
public final class PypiMetadataMerger implements MetadataMerger {

    /**
     * Pattern to extract anchor tags from HTML.
     * Captures: group(1) = href value, group(2) = link text
     */
    private static final Pattern LINK_PATTERN = Pattern.compile(
        "<a\\s+href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * HTML template header.
     */
    private static final String HTML_HEADER =
        "<!DOCTYPE html>\n<html>\n<body>\n";

    /**
     * HTML template footer.
     */
    private static final String HTML_FOOTER = "</body>\n</html>";

    @Override
    public byte[] merge(final LinkedHashMap<String, byte[]> responses) {
        // TreeMap for sorted, deduplicated entries (key = filename, value = full href)
        final TreeMap<String, String> links = new TreeMap<>();
        for (final Map.Entry<String, byte[]> entry : responses.entrySet()) {
            final String html = new String(entry.getValue(), StandardCharsets.UTF_8);
            final Matcher matcher = LINK_PATTERN.matcher(html);
            while (matcher.find()) {
                final String href = matcher.group(1);
                final String filename = extractFilename(href);
                // First occurrence wins (priority order)
                if (!links.containsKey(filename)) {
                    links.put(filename, href);
                }
            }
        }
        return generateHtml(links);
    }

    /**
     * Extract filename from href, handling various URL formats.
     *
     * @param href The href value
     * @return Filename (or full href if no path separator found)
     */
    private static String extractFilename(final String href) {
        // Remove hash fragment if present (e.g., #sha256=...)
        final String withoutHash;
        final int hashIdx = href.indexOf('#');
        if (hashIdx >= 0) {
            withoutHash = href.substring(0, hashIdx);
        } else {
            withoutHash = href;
        }
        // Extract filename from path
        final int lastSlash = withoutHash.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < withoutHash.length() - 1) {
            return withoutHash.substring(lastSlash + 1);
        }
        return withoutHash;
    }

    /**
     * Generate HTML output from sorted links.
     *
     * @param links Map of filename to href
     * @return HTML bytes
     */
    private static byte[] generateHtml(final TreeMap<String, String> links) {
        final StringBuilder html = new StringBuilder(HTML_HEADER);
        for (final Map.Entry<String, String> entry : links.entrySet()) {
            html.append("<a href=\"")
                .append(entry.getValue())
                .append("\">")
                .append(entry.getKey())
                .append("</a>\n");
        }
        html.append(HTML_FOOTER);
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }
}
