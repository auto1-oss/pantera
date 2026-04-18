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
     */
    public record Link(
        String href,
        String filename,
        String version,
        String requiresPython,
        String distInfoMetadata
    ) {
        /**
         * Constructor.
         *
         * @param href Href attribute value
         * @param filename Link text
         * @param version Extracted version
         * @param requiresPython data-requires-python value
         * @param distInfoMetadata data-dist-info-metadata value
         */
        public Link {
            Objects.requireNonNull(href, "href must not be null");
            Objects.requireNonNull(filename, "filename must not be null");
        }
    }
}
