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

import java.util.List;
import java.util.Objects;

/**
 * Parsed representation of a PyPI Simple Index page.
 * Contains the original HTML and a list of parsed {@link Link} records extracted
 * from {@code <a>} elements.
 *
 * @param originalHtml The original HTML source (preserved for reconstruction)
 * @param links Extracted link records
 * @since 2.2.0
 */
public record PypiSimpleIndex(String originalHtml, List<Link> links) {

    /**
     * Constructor.
     *
     * @param originalHtml Original HTML source
     * @param links Parsed link records
     */
    public PypiSimpleIndex {
        Objects.requireNonNull(originalHtml, "originalHtml must not be null");
        Objects.requireNonNull(links, "links must not be null");
        links = List.copyOf(links);
    }

    /**
     * A single link from the PyPI Simple Index page.
     *
     * @param href The href attribute value (includes hash fragment)
     * @param filename The link text (distribution filename)
     * @param version Extracted version from filename, or null if unparseable
     * @param requiresPython The data-requires-python attribute, or null
     * @param distInfoMetadata The data-dist-info-metadata attribute, or null
     * @param uploadTime The {@code data-upload-time} attribute (PEP 700,
     *                   ISO 8601 — e.g. {@code 2024-09-09T15:12:34.567890Z}),
     *                   or null when the upstream index doesn't emit it.
     *                   Used by the cooldown filter to skip the
     *                   {@code inspector.releaseDate} fetch — same shortcut
     *                   the npm/composer adapters take with packument-inline
     *                   timestamps (commit {@code dbdde1736}).
     * @param yanked PEP 592 yank status. {@code null} means not yanked;
     *               a non-null value (possibly empty string) is the yank
     *               reason and means the file IS yanked. Wire form:
     *               HTML {@code data-yanked="<reason>"} attribute (absent
     *               when not yanked); JSON boolean {@code false} (not
     *               yanked) or a string (yanked, possibly empty reason).
     */
    public record Link(
        String href,
        String filename,
        String version,
        String requiresPython,
        String distInfoMetadata,
        String uploadTime,
        String yanked
    ) {
        /**
         * Constructor.
         *
         * @param href Href attribute value
         * @param filename Link text
         * @param version Extracted version
         * @param requiresPython data-requires-python value
         * @param distInfoMetadata data-dist-info-metadata value
         * @param uploadTime data-upload-time value (PEP 700)
         */
        public Link {
            Objects.requireNonNull(href, "href must not be null");
            Objects.requireNonNull(filename, "filename must not be null");
        }
    }
}
