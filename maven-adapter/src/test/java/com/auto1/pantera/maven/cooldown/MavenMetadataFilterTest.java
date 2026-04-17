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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Tests for {@link MavenMetadataFilter}.
 *
 * @since 2.2.0
 */
final class MavenMetadataFilterTest {

    private MavenMetadataParser parser;
    private MavenMetadataFilter filter;

    @BeforeEach
    void setUp() {
        this.parser = new MavenMetadataParser();
        this.filter = new MavenMetadataFilter();
    }

    @Test
    void filtersThreeOfTenVersions() throws Exception {
        final Document doc = this.parser.parse(
            MavenMetadataFilterTest.loadFixture()
        );
        final Document filtered = this.filter.filter(
            doc, Set.of("2.0.0", "3.0.0-alpha", "3.0.0-beta")
        );
        final List<String> versions = this.parser.extractVersions(filtered);
        assertThat(versions, hasSize(7));
        assertThat(
            versions,
            contains(
                "1.0.0", "1.1.0", "1.2.0",
                "2.1.0", "2.2.0", "2.3.0",
                "3.0.0"
            )
        );
    }

    @Test
    void allBlockedResultsInEmptyVersions() throws Exception {
        final Document doc = this.parser.parse(
            MavenMetadataFilterTest.loadFixture()
        );
        final Set<String> all = Set.of(
            "1.0.0", "1.1.0", "1.2.0",
            "2.0.0", "2.1.0", "2.2.0", "2.3.0",
            "3.0.0-alpha", "3.0.0-beta", "3.0.0"
        );
        final Document filtered = this.filter.filter(doc, all);
        final List<String> versions = this.parser.extractVersions(filtered);
        assertThat(versions, is(empty()));
    }

    @Test
    void noneBlockedLeavesAllVersions() throws Exception {
        final Document doc = this.parser.parse(
            MavenMetadataFilterTest.loadFixture()
        );
        final Document filtered = this.filter.filter(
            doc, Collections.emptySet()
        );
        final List<String> versions = this.parser.extractVersions(filtered);
        assertThat(versions, hasSize(10));
    }

    @Test
    void updatesLatestElement() throws Exception {
        final Document doc = this.parser.parse(
            MavenMetadataFilterTest.loadFixture()
        );
        this.filter.updateLatest(doc, "2.3.0");
        final String latest = this.parser.getLatestVersion(doc).orElse("");
        assertThat(latest, equalTo("2.3.0"));
    }

    @Test
    void updatesLastUpdatedTimestamp() throws Exception {
        final Document doc = this.parser.parse(
            MavenMetadataFilterTest.loadFixture()
        );
        final String before = doc.getElementsByTagName("lastUpdated")
            .item(0).getTextContent();
        this.filter.updateLatest(doc, "2.3.0");
        final String after = doc.getElementsByTagName("lastUpdated")
            .item(0).getTextContent();
        assertThat(after, is(not(equalTo(before))));
        // Maven timestamp format: yyyyMMddHHmmss -> 14 digits
        assertThat(after.length(), equalTo(14));
    }

    /**
     * Load the sample maven-metadata.xml fixture from test resources.
     *
     * @return Fixture bytes
     * @throws IOException If reading fails
     */
    private static byte[] loadFixture() throws IOException {
        try (InputStream stream = MavenMetadataFilterTest.class
            .getResourceAsStream("/cooldown/maven-metadata-sample.xml")) {
            if (stream == null) {
                throw new IOException(
                    "Fixture not found: /cooldown/maven-metadata-sample.xml"
                );
            }
            return stream.readAllBytes();
        }
    }
}
