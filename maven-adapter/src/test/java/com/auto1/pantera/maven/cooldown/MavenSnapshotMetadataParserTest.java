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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Phase C parser tests for SNAPSHOT-level Maven metadata.
 *
 * @since 2.2.0
 */
class MavenSnapshotMetadataParserTest {

    private static final String XML =
        "<?xml version=\"1.0\"?>\n"
        + "<metadata>\n"
        + "  <groupId>org.foo</groupId>\n"
        + "  <artifactId>lib</artifactId>\n"
        + "  <version>1.0-SNAPSHOT</version>\n"
        + "  <versioning>\n"
        + "    <snapshot>\n"
        + "      <timestamp>20260519.090000</timestamp>\n"
        + "      <buildNumber>2</buildNumber>\n"
        + "    </snapshot>\n"
        + "    <lastUpdated>20260519090000</lastUpdated>\n"
        + "    <snapshotVersions>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>jar</extension>\n"
        + "        <value>1.0-20260519.090000-2</value>\n"
        + "        <updated>20260519090000</updated>\n"
        + "      </snapshotVersion>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>pom</extension>\n"
        + "        <value>1.0-20260519.090000-2</value>\n"
        + "        <updated>20260519090000</updated>\n"
        + "      </snapshotVersion>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>jar</extension>\n"
        + "        <value>1.0-20260518.080000-1</value>\n"
        + "        <updated>20260518080000</updated>\n"
        + "      </snapshotVersion>\n"
        + "    </snapshotVersions>\n"
        + "  </versioning>\n"
        + "</metadata>\n";

    @Test
    void extractsAllSnapshotVersionValues() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        final List<String> values = parser.extractVersions(doc);
        MatcherAssert.assertThat(
            "extractVersions must return all three snapshotVersion entries",
            values.size(), new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "value 0", values.get(0), new IsEqual<>("1.0-20260519.090000-2")
        );
        MatcherAssert.assertThat(
            "value 2", values.get(2), new IsEqual<>("1.0-20260518.080000-1")
        );
    }

    @Test
    void getLatestVersionCombinesTimestampAndBuildNumber() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        MatcherAssert.assertThat(
            parser.getLatestVersion(doc),
            new IsEqual<>(Optional.of("20260519.090000-2"))
        );
    }

    @Test
    void extractReleaseDatesReturnsValueToUpdatedInstantMap() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        final Map<String, Instant> dates = parser.extractReleaseDates(doc);
        MatcherAssert.assertThat(
            "at least two release dates must be parsed",
            dates.size() >= 2, new IsEqual<>(true)
        );
        final Instant expected = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneOffset.UTC)
            .parse("20260519090000", Instant::from);
        MatcherAssert.assertThat(
            "newest entry release date",
            dates.get("1.0-20260519.090000-2"), new IsEqual<>(expected)
        );
    }
}
