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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link MavenMetadataParser}.
 *
 * @since 2.2.0
 */
final class MavenMetadataParserTest {

    private MavenMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new MavenMetadataParser();
    }

    @Test
    void parsesSampleMetadataFixture() throws Exception {
        final byte[] xml = MavenMetadataParserTest.loadFixture();
        final Document doc = this.parser.parse(xml);
        assertThat(doc, is(notNullValue()));
    }

    @Test
    void extractsTenVersionsFromFixture() throws Exception {
        final byte[] xml = MavenMetadataParserTest.loadFixture();
        final Document doc = this.parser.parse(xml);
        final List<String> versions = this.parser.extractVersions(doc);
        assertThat(versions, hasSize(10));
        assertThat(
            versions,
            contains(
                "1.0.0", "1.1.0", "1.2.0",
                "2.0.0", "2.1.0", "2.2.0", "2.3.0",
                "3.0.0-alpha", "3.0.0-beta", "3.0.0"
            )
        );
    }

    @Test
    void getsLatestVersionFromFixture() throws Exception {
        final byte[] xml = MavenMetadataParserTest.loadFixture();
        final Document doc = this.parser.parse(xml);
        final Optional<String> latest = this.parser.getLatestVersion(doc);
        assertThat(latest.isPresent(), is(true));
        assertThat(latest.get(), equalTo("3.0.0"));
    }

    @Test
    void returnsEmptyListWhenNoVersions() throws Exception {
        final String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>empty</artifactId>
            </metadata>
            """;
        final Document doc = this.parser.parse(
            xml.getBytes(StandardCharsets.UTF_8)
        );
        final List<String> versions = this.parser.extractVersions(doc);
        assertThat(versions, is(empty()));
    }

    @Test
    void returnsEmptyWhenNoLatest() throws Exception {
        final String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>com.example</groupId>
              <artifactId>no-latest</artifactId>
              <versioning>
                <versions>
                  <version>1.0.0</version>
                </versions>
              </versioning>
            </metadata>
            """;
        final Document doc = this.parser.parse(
            xml.getBytes(StandardCharsets.UTF_8)
        );
        final Optional<String> latest = this.parser.getLatestVersion(doc);
        assertThat(latest.isPresent(), is(false));
    }

    @Test
    void extractReleaseDatesReturnsEmptyMap() throws Exception {
        final byte[] xml = MavenMetadataParserTest.loadFixture();
        final Document doc = this.parser.parse(xml);
        final Map<String, Instant> dates = this.parser.extractReleaseDates(doc);
        assertThat(dates.isEmpty(), is(true));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.parser.contentType(), equalTo("application/xml"));
    }

    @Test
    void throwsOnInvalidXml() {
        final byte[] invalid = "not valid xml <<<".getBytes(
            StandardCharsets.UTF_8
        );
        assertThrows(
            MetadataParseException.class,
            () -> this.parser.parse(invalid)
        );
    }

    /**
     * Load the sample maven-metadata.xml fixture from test resources.
     *
     * @return Fixture bytes
     * @throws IOException If reading fails
     */
    private static byte[] loadFixture() throws IOException {
        try (InputStream stream = MavenMetadataParserTest.class
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
