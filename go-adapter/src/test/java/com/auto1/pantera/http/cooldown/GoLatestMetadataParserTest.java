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
package com.auto1.pantera.http.cooldown;

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link GoLatestMetadataParser}.
 *
 * @since 2.2.0
 */
final class GoLatestMetadataParserTest {

    private GoLatestMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new GoLatestMetadataParser();
    }

    @Test
    void parsesVersionAndTime() throws MetadataParseException {
        final String json = "{\"Version\":\"v1.2.3\",\"Time\":\"2024-05-12T00:00:00Z\"}";
        final GoLatestInfo info = this.parser.parse(
            json.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(info.version(), equalTo("v1.2.3"));
        assertThat(info.time(), equalTo("2024-05-12T00:00:00Z"));
        assertThat(info.origin(), is(nullValue()));
    }

    @Test
    void parsesOriginObject() throws MetadataParseException {
        final String json = "{\"Version\":\"v1.2.3\","
            + "\"Time\":\"2024-05-12T00:00:00Z\","
            + "\"Origin\":{\"VCS\":\"git\",\"URL\":\"https://example.com\"}}";
        final GoLatestInfo info = this.parser.parse(
            json.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(info.origin(), is(notNullValue()));
        assertThat(info.origin().get("VCS").asText(), equalTo("git"));
    }

    @Test
    void parsesPseudoVersion() throws MetadataParseException {
        final String json =
            "{\"Version\":\"v0.0.0-20240101000000-abc123def456\"}";
        final GoLatestInfo info = this.parser.parse(
            json.getBytes(StandardCharsets.UTF_8)
        );
        assertThat(info.version(), equalTo("v0.0.0-20240101000000-abc123def456"));
        // Time absent in pseudo-version responses is normal per Go proxy spec
        assertThat(info.time(), is(nullValue()));
    }

    @Test
    void extractVersionsReturnsSingleton() throws MetadataParseException {
        final GoLatestInfo info = this.parser.parse(
            "{\"Version\":\"v1.2.3\"}".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(this.parser.extractVersions(info), contains("v1.2.3"));
    }

    @Test
    void getLatestVersionReturnsTheVersion() throws MetadataParseException {
        final GoLatestInfo info = this.parser.parse(
            "{\"Version\":\"v1.2.3\"}".getBytes(StandardCharsets.UTF_8)
        );
        assertThat(this.parser.getLatestVersion(info).isPresent(), is(true));
        assertThat(this.parser.getLatestVersion(info).get(), equalTo("v1.2.3"));
    }

    @Test
    void throwsOnEmptyBody() {
        assertThrows(MetadataParseException.class,
            () -> this.parser.parse(new byte[0]));
    }

    @Test
    void throwsOnNullBody() {
        assertThrows(MetadataParseException.class,
            () -> this.parser.parse(null));
    }

    @Test
    void throwsOnMalformedJson() {
        assertThrows(MetadataParseException.class,
            () -> this.parser.parse("not a json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void throwsOnMissingVersionField() {
        assertThrows(MetadataParseException.class,
            () -> this.parser.parse(
                "{\"Time\":\"2024-05-12T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8)
            )
        );
    }

    @Test
    void throwsOnNonObjectRoot() {
        assertThrows(MetadataParseException.class,
            () -> this.parser.parse("[]".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void returnsJsonContentType() {
        assertThat(this.parser.contentType(), equalTo("application/json"));
    }
}
