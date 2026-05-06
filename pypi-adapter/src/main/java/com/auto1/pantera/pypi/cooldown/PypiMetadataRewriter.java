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
package com.auto1.pantera.pypi.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataRewriteException;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;

import java.nio.charset.StandardCharsets;

/**
 * PyPI metadata rewriter implementing cooldown SPI.
 * Serializes a filtered {@link PypiSimpleIndex} back to PEP 503 compliant HTML.
 *
 * <p>The output format follows the PyPI Simple Repository API (PEP 503):</p>
 * <pre>
 * &lt;!DOCTYPE html&gt;&lt;html&gt;&lt;body&gt;
 * &lt;a href="..."&gt;filename.tar.gz&lt;/a&gt;
 * ...
 * &lt;/body&gt;&lt;/html&gt;
 * </pre>
 *
 * @since 2.2.0
 */
public final class PypiMetadataRewriter implements MetadataRewriter<PypiSimpleIndex> {

    /**
     * Content type for PyPI Simple Index HTML.
     */
    private static final String CONTENT_TYPE = "text/html";

    @Override
    public byte[] rewrite(final PypiSimpleIndex metadata) throws MetadataRewriteException {
        try {
            final StringBuilder html = new StringBuilder(512);
            html.append("<!DOCTYPE html><html><body>\n");
            for (final PypiSimpleIndex.Link link : metadata.links()) {
                html.append("<a href=\"");
                html.append(escapeHtml(link.href()));
                html.append('"');
                if (link.requiresPython() != null && !link.requiresPython().isEmpty()) {
                    html.append(" data-requires-python=\"");
                    html.append(escapeHtml(link.requiresPython()));
                    html.append('"');
                }
                if (link.distInfoMetadata() != null && !link.distInfoMetadata().isEmpty()) {
                    html.append(" data-dist-info-metadata=\"");
                    html.append(escapeHtml(link.distInfoMetadata()));
                    html.append('"');
                }
                html.append('>');
                html.append(escapeHtml(link.filename()));
                html.append("</a>\n");
            }
            html.append("</body></html>");
            return html.toString().getBytes(StandardCharsets.UTF_8);
        } catch (final Exception ex) {
            throw new MetadataRewriteException(
                "Failed to serialize PyPI Simple Index to HTML", ex
            );
        }
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    /**
     * Minimal HTML escaping for attribute values and text content.
     * Escapes {@code &}, {@code <}, {@code >}, and {@code "}.
     *
     * @param value Raw string
     * @return HTML-escaped string
     */
    private static String escapeHtml(final String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
