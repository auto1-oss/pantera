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
 * Filters and rewrites snapshot-level {@code maven-metadata.xml}. Removes
 * {@code <snapshotVersion>} entries whose {@code <value>} is in the blocked
 * set, and rewrites {@code <snapshot><timestamp>}/{@code <snapshot><buildNumber>}
 * to point at the surviving newest timestamped build.
 *
 * @since 2.2.0
 */
public final class MavenSnapshotMetadataFilter implements MetadataFilter<Document> {

    /**
     * Maven {@code <lastUpdated>} timestamp format (no separators).
     */
    private static final DateTimeFormatter LAST_UPDATED_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    public Document filter(final Document metadata, final Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        final NodeList entries = metadata.getElementsByTagName("snapshotVersion");
        // Iterate in reverse so removals don't shift subsequent indexes.
        for (int idx = entries.getLength() - 1; idx >= 0; idx--) {
            final Element entry = (Element) entries.item(idx);
            final String value = childText(entry, "value");
            if (value != null && blockedVersions.contains(value.trim())) {
                entry.getParentNode().removeChild(entry);
            }
        }
        setText(metadata, "lastUpdated", ZonedDateTime.now(ZoneOffset.UTC).format(LAST_UPDATED_TS));
        return metadata;
    }

    @Override
    public Document updateLatest(final Document metadata, final String newLatest) {
        // newLatest is the timestamped form yyyyMMdd.HHmmss-N — split on the
        // final '-' so buildNumber stays correct even when the timestamp
        // portion itself contains a dot.
        final int dash = newLatest.lastIndexOf('-');
        if (dash <= 0 || dash >= newLatest.length() - 1) {
            return metadata;
        }
        final String timestamp = newLatest.substring(0, dash);
        final String buildNumber = newLatest.substring(dash + 1);
        rewriteSnapshot(metadata, timestamp, buildNumber);
        setText(metadata, "lastUpdated", ZonedDateTime.now(ZoneOffset.UTC).format(LAST_UPDATED_TS));
        return metadata;
    }

    /**
     * Rewrite the single {@code <snapshot>} element's {@code <timestamp>} and
     * {@code <buildNumber>} children. Creates them if they were absent
     * (rare, but the upstream registry occasionally omits one of the two
     * during a partial publish race).
     */
    private static void rewriteSnapshot(
        final Document doc, final String timestamp, final String buildNumber
    ) {
        final NodeList nodes = doc.getElementsByTagName("snapshot");
        if (nodes.getLength() == 0) {
            return;
        }
        final Element snapshot = (Element) nodes.item(0);
        setChildText(snapshot, "timestamp", timestamp);
        setChildText(snapshot, "buildNumber", buildNumber);
    }

    private static void setChildText(final Element parent, final String tag, final String value) {
        final NodeList children = parent.getChildNodes();
        for (int idx = 0; idx < children.getLength(); idx++) {
            final Node child = children.item(idx);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                child.setTextContent(value);
                return;
            }
        }
        final Element created = parent.getOwnerDocument().createElement(tag);
        created.setTextContent(value);
        parent.appendChild(created);
    }

    private static void setText(final Document doc, final String tag, final String value) {
        final NodeList nodes = doc.getElementsByTagName(tag);
        if (nodes.getLength() > 0) {
            nodes.item(0).setTextContent(value);
            return;
        }
        final NodeList versioning = doc.getElementsByTagName("versioning");
        if (versioning.getLength() > 0) {
            final Element created = doc.createElement(tag);
            created.setTextContent(value);
            versioning.item(0).appendChild(created);
        }
    }

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
