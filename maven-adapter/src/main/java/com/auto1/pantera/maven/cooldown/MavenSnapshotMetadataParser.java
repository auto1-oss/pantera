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

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maven snapshot-level metadata parser. Targets the per-SNAPSHOT-directory
 * {@code maven-metadata.xml} that lists individual {@code snapshotVersion}
 * entries with their timestamped {@code value} and {@code updated} timestamp.
 *
 * <p>Snapshot metadata structure:</p>
 * <pre>
 * &lt;metadata&gt;
 *   &lt;versioning&gt;
 *     &lt;snapshot&gt;
 *       &lt;timestamp&gt;20260519.090000&lt;/timestamp&gt;
 *       &lt;buildNumber&gt;1&lt;/buildNumber&gt;
 *     &lt;/snapshot&gt;
 *     &lt;snapshotVersions&gt;
 *       &lt;snapshotVersion&gt;
 *         &lt;extension&gt;jar&lt;/extension&gt;
 *         &lt;value&gt;1.0-20260519.090000-1&lt;/value&gt;
 *         &lt;updated&gt;20260519090000&lt;/updated&gt;
 *       &lt;/snapshotVersion&gt;
 *     &lt;/snapshotVersions&gt;
 *   &lt;/versioning&gt;
 * &lt;/metadata&gt;
 * </pre>
 *
 * @since 2.2.0
 */
public final class MavenSnapshotMetadataParser implements MetadataParser<Document> {

    /**
     * Content type for Maven metadata.
     */
    private static final String CONTENT_TYPE = "application/xml";

    /**
     * Format used in {@code <snapshotVersion><updated>} (no separators).
     */
    private static final DateTimeFormatter UPDATED_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    public Document parse(final byte[] bytes) {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true
            );
            final DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (final SAXException | IOException | ParserConfigurationException ex) {
            throw new MetadataParseException(
                "Failed to parse Maven snapshot metadata XML", ex
            );
        }
    }

    @Override
    public List<String> extractVersions(final Document metadata) {
        final NodeList nodes = metadata.getElementsByTagName("snapshotVersion");
        final List<String> values = new ArrayList<>(nodes.getLength());
        for (int idx = 0; idx < nodes.getLength(); idx++) {
            final String value = childText((Element) nodes.item(idx), "value");
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values;
    }

    @Override
    public Optional<String> getLatestVersion(final Document metadata) {
        final NodeList snapshots = metadata.getElementsByTagName("snapshot");
        if (snapshots.getLength() == 0) {
            return Optional.empty();
        }
        final Element snapshot = (Element) snapshots.item(0);
        final String timestamp = childText(snapshot, "timestamp");
        final String buildNumber = childText(snapshot, "buildNumber");
        if (timestamp == null || timestamp.isBlank()
            || buildNumber == null || buildNumber.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(timestamp.trim() + "-" + buildNumber.trim());
    }

    @Override
    public String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public Map<String, Instant> extractReleaseDates(final Document metadata) {
        final NodeList nodes = metadata.getElementsByTagName("snapshotVersion");
        final Map<String, Instant> dates = new HashMap<>(nodes.getLength());
        for (int idx = 0; idx < nodes.getLength(); idx++) {
            final Element entry = (Element) nodes.item(idx);
            final String value = childText(entry, "value");
            final String updated = childText(entry, "updated");
            if (value == null || value.isBlank()
                || updated == null || updated.isBlank()) {
                continue;
            }
            try {
                final LocalDateTime ldt = LocalDateTime.parse(updated.trim(), UPDATED_TS);
                dates.put(value.trim(), ldt.toInstant(ZoneOffset.UTC));
            } catch (final DateTimeParseException ignored) {
                // Skip entries with malformed timestamps; the inspector path
                // will fall through to its normal lookup for those.
            }
        }
        return dates;
    }

    /**
     * Read the first child element with the given tag name as text. Returns
     * null when absent. Used instead of {@link Document#getElementsByTagName}
     * so a stray descendant in another scope doesn't shadow the intended
     * direct child.
     */
    private static String childText(final Element parent, final String tag) {
        final NodeList children = parent.getChildNodes();
        for (int idx = 0; idx < children.getLength(); idx++) {
            final Node child = children.item(idx);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }
}
