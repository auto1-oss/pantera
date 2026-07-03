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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link GoLatestMetadataRewriter}.
 *
 * @since 2.2.0
 */
final class GoLatestMetadataRewriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoLatestMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.rewriter = new GoLatestMetadataRewriter();
    }

    @Test
    void emitsVersionOnly() throws IOException {
        final GoLatestInfo info = new GoLatestInfo("v1.2.3", null, null);
        final byte[] out = this.rewriter.rewrite(info);
        final JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("Version").asText(), equalTo("v1.2.3"));
        assertThat(node.get("Time"), is(nullValue()));
        assertThat(node.get("Origin"), is(nullValue()));
    }

    @Test
    void emitsVersionAndTime() throws IOException {
        final GoLatestInfo info = new GoLatestInfo(
            "v1.2.3", "2024-05-12T00:00:00Z", null
        );
        final byte[] out = this.rewriter.rewrite(info);
        final JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("Version").asText(), equalTo("v1.2.3"));
        assertThat(node.get("Time").asText(), equalTo("2024-05-12T00:00:00Z"));
    }

    @Test
    void preservesOriginWhenPresent() throws IOException {
        final ObjectNode origin = MAPPER.createObjectNode();
        origin.put("VCS", "git");
        origin.put("URL", "https://github.com/foo/bar");
        final GoLatestInfo info = new GoLatestInfo(
            "v1.2.3", null, origin
        );
        final byte[] out = this.rewriter.rewrite(info);
        final JsonNode node = MAPPER.readTree(out);
        assertThat(node.get("Origin"), is(not(nullValue())));
        assertThat(node.get("Origin").get("VCS").asText(), equalTo("git"));
        assertThat(
            node.get("Origin").get("URL").asText(),
            equalTo("https://github.com/foo/bar")
        );
    }

    @Test
    void emptyBytesWhenMetadataNull() {
        final byte[] out = this.rewriter.rewrite(null);
        assertThat(out.length, equalTo(0));
    }

    @Test
    void returnsJsonContentType() {
        assertThat(this.rewriter.contentType(), equalTo("application/json"));
    }
}
