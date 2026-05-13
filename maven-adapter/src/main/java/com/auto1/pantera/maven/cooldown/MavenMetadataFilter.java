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
package com.auto1.pantera.maven.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * Maven metadata filter implementing cooldown SPI.
 * Removes blocked versions from {@code maven-metadata.xml}.
 *
 * <p>Filters the following elements:</p>
 * <ul>
 *   <li>{@code <versions><version>} - removes blocked version nodes</li>
 *   <li>{@code <latest>} - updated when current latest is blocked</li>
 *   <li>{@code <release>} - updated to the latest non-SNAPSHOT version
 *       that survives the filter; Gradle's {@code latest.release}
 *       resolution reads this element, so a stale {@code <release>}
 *       still pointing at a blocked version makes the build pick a
 *       version it cannot subsequently download (HTTP 403)</li>
 *   <li>{@code <lastUpdated>} - set to current timestamp on modification</li>
 * </ul>
 *
 * @since 2.2.0
 */
public final class MavenMetadataFilter implements MetadataFilter<Document> {

    /**
     * Maven metadata timestamp format: yyyyMMddHHmmss.
     */
    private static final DateTimeFormatter MAVEN_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    public Document filter(
        final Document metadata, final Set<String> blockedVersions
    ) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        final NodeList versionNodes = metadata.getElementsByTagName("version");
        for (int idx = versionNodes.getLength() - 1; idx >= 0; idx--) {
            final Node node = versionNodes.item(idx);
            final String text = node.getTextContent();
            if (text != null && blockedVersions.contains(text.trim())) {
                node.getParentNode().removeChild(node);
            }
        }
        return metadata;
    }

    @Override
    public Document updateLatest(
        final Document metadata, final String newLatest
    ) {
        MavenMetadataFilter.setElementText(metadata, "latest", newLatest);
        // Recompute <release> from the filtered version list. <release> by
        // Maven convention is the latest non-SNAPSHOT version actually
        // available in the repository — the most-recent <version> entry that
        // doesn't end with -SNAPSHOT. If newLatest itself qualifies, prefer
        // that (consistent with the cooldown service's release-date ordering
        // which gave us newLatest); otherwise scan the filtered version list.
        final String newRelease = computeNewRelease(metadata, newLatest);
        if (newRelease != null) {
            MavenMetadataFilter.setElementText(metadata, "release", newRelease);
        }
        final String now = ZonedDateTime.now(ZoneOffset.UTC).format(MAVEN_TS);
        MavenMetadataFilter.setElementText(metadata, "lastUpdated", now);
        return metadata;
    }

    /**
     * Pick the new {@code <release>} value: latest non-SNAPSHOT version
     * still present in the (already-filtered) {@code <versions>} list.
     * Falls back to {@code newLatest} if it itself is non-SNAPSHOT and to
     * {@code null} (no rewrite) if no non-SNAPSHOT survives.
     */
    private static String computeNewRelease(
        final Document metadata, final String newLatest
    ) {
        if (newLatest != null && !newLatest.endsWith("-SNAPSHOT")) {
            return newLatest;
        }
        final NodeList versionNodes = metadata.getElementsByTagName("version");
        // Iterate in document order — Maven's metadata convention is
        // ascending order, so the LAST non-SNAPSHOT entry is the newest.
        String candidate = null;
        for (int idx = 0; idx < versionNodes.getLength(); idx++) {
            final String text = versionNodes.item(idx).getTextContent();
            if (text == null) {
                continue;
            }
            final String trimmed = text.trim();
            if (!trimmed.isEmpty() && !trimmed.endsWith("-SNAPSHOT")) {
                candidate = trimmed;
            }
        }
        return candidate;
    }

    /**
     * Set the text content of the first element with the given tag name.
     * Creates the element under {@code <versioning>} if it does not exist.
     *
     * @param doc Document to modify
     * @param tag Element tag name
     * @param value New text content
     */
    private static void setElementText(
        final Document doc, final String tag, final String value
    ) {
        final NodeList nodes = doc.getElementsByTagName(tag);
        if (nodes.getLength() > 0) {
            nodes.item(0).setTextContent(value);
        } else {
            final NodeList versioning = doc.getElementsByTagName("versioning");
            if (versioning.getLength() > 0) {
                final Element elem = doc.createElement(tag);
                elem.setTextContent(value);
                versioning.item(0).appendChild(elem);
            }
        }
    }
}
