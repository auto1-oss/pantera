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

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Phase C filter tests: removing blocked timestamped SNAPSHOT entries and
 * rewriting the surviving newest into {@code <snapshot><timestamp>}/
 * {@code <buildNumber>}.
 *
 * @since 2.2.0
 */
class MavenSnapshotMetadataFilterTest {

    private static final String XML =
        "<?xml version=\"1.0\"?>\n"
        + "<metadata>\n"
        + "  <versioning>\n"
        + "    <snapshot>\n"
        + "      <timestamp>20260519.090000</timestamp>\n"
        + "      <buildNumber>3</buildNumber>\n"
        + "    </snapshot>\n"
        + "    <lastUpdated>20260519090000</lastUpdated>\n"
        + "    <snapshotVersions>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>jar</extension>\n"
        + "        <value>1.0-20260519.090000-3</value>\n"
        + "        <updated>20260519090000</updated>\n"
        + "      </snapshotVersion>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>jar</extension>\n"
        + "        <value>1.0-20260518.080000-2</value>\n"
        + "        <updated>20260518080000</updated>\n"
        + "      </snapshotVersion>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>jar</extension>\n"
        + "        <value>1.0-20260101.000000-1</value>\n"
        + "        <updated>20260101000000</updated>\n"
        + "      </snapshotVersion>\n"
        + "    </snapshotVersions>\n"
        + "  </versioning>\n"
        + "</metadata>\n";

    @Test
    void filterRemovesBlockedTimestampedEntries() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final MavenSnapshotMetadataFilter filter = new MavenSnapshotMetadataFilter();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        filter.filter(doc, Set.of(
            "1.0-20260519.090000-3",
            "1.0-20260518.080000-2"
        ));
        final NodeList survivors = doc.getElementsByTagName("snapshotVersion");
        MatcherAssert.assertThat(
            "two newer entries must be stripped",
            survivors.getLength(), new IsEqual<>(1)
        );
        final Element entry = (Element) survivors.item(0);
        final NodeList values = entry.getElementsByTagName("value");
        MatcherAssert.assertThat(
            "remaining entry is the oldest one",
            values.item(0).getTextContent(), new IsEqual<>("1.0-20260101.000000-1")
        );
    }

    @Test
    void updateLatestRewritesSnapshotTimestampAndBuildNumber() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final MavenSnapshotMetadataFilter filter = new MavenSnapshotMetadataFilter();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        filter.updateLatest(doc, "20260101.000000-1");
        final Element snapshot = (Element) doc.getElementsByTagName("snapshot").item(0);
        MatcherAssert.assertThat(
            "timestamp",
            snapshot.getElementsByTagName("timestamp").item(0).getTextContent(),
            new IsEqual<>("20260101.000000")
        );
        MatcherAssert.assertThat(
            "buildNumber",
            snapshot.getElementsByTagName("buildNumber").item(0).getTextContent(),
            new IsEqual<>("1")
        );
    }

    @Test
    void filterBumpsLastUpdated() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final MavenSnapshotMetadataFilter filter = new MavenSnapshotMetadataFilter();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        filter.filter(doc, Set.of("1.0-20260519.090000-3"));
        final String lastUpdated =
            doc.getElementsByTagName("lastUpdated").item(0).getTextContent();
        MatcherAssert.assertThat(
            "lastUpdated must be rewritten to a 14-digit timestamp",
            lastUpdated.matches("\\d{14}"), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "lastUpdated must NOT remain the original value",
            !"20260519090000".equals(lastUpdated), new IsEqual<>(true)
        );
    }
}
