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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Tests for {@link PypiMetadataRewriter}.
 *
 * @since 2.2.0
 */
final class PypiMetadataRewriterTest {

    private PypiMetadataParser parser;
    private PypiMetadataFilter filter;
    private PypiMetadataRewriter rewriter;

    @BeforeEach
    void setUp() {
        this.parser = new PypiMetadataParser();
        this.filter = new PypiMetadataFilter();
        this.rewriter = new PypiMetadataRewriter();
    }

    @Test
    void roundTripPreservesAllLinks() throws Exception {
        final byte[] original = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(original);

        final byte[] rewritten = this.rewriter.rewrite(index);
        final PypiSimpleIndex reparsed = this.parser.parse(rewritten);

        assertThat(reparsed.links(), hasSize(index.links().size()));

        final List<String> originalVersions = this.parser.extractVersions(index);
        final List<String> reparsedVersions = this.parser.extractVersions(reparsed);
        assertThat(reparsedVersions, equalTo(originalVersions));
    }

    @Test
    void roundTripAfterFilter() throws Exception {
        final byte[] original = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(original);

        // Filter out 3 versions
        final PypiSimpleIndex filtered = this.filter.filter(
            index, Set.of("1.0.0", "2.0.0", "2.1.0a1")
        );

        // Rewrite
        final byte[] rewritten = this.rewriter.rewrite(filtered);

        // Re-parse and verify
        final PypiSimpleIndex reparsed = this.parser.parse(rewritten);
        final List<String> versions = this.parser.extractVersions(reparsed);

        assertThat("1.0.0 should be removed", versions.contains("1.0.0"), is(false));
        assertThat("2.0.0 should be removed", versions.contains("2.0.0"), is(false));
        assertThat("2.1.0a1 should be removed", versions.contains("2.1.0a1"), is(false));
        assertThat("0.9.0 should remain", versions.contains("0.9.0"), is(true));
        assertThat("1.1.0 should remain", versions.contains("1.1.0"), is(true));
        assertThat("2.1.0 should remain", versions.contains("2.1.0"), is(true));
    }

    @Test
    void outputIsValidHtml() throws Exception {
        final byte[] original = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(original);
        final byte[] rewritten = this.rewriter.rewrite(index);
        final String html = new String(rewritten, StandardCharsets.UTF_8);

        assertThat(html, startsWith("<!DOCTYPE html><html><body>"));
        assertThat(html, containsString("</body></html>"));
    }

    @Test
    void preservesRequiresPythonAttribute() throws Exception {
        final byte[] original = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(original);
        final byte[] rewritten = this.rewriter.rewrite(index);
        final String html = new String(rewritten, StandardCharsets.UTF_8);

        assertThat(html, containsString("data-requires-python="));
    }

    @Test
    void preservesDistInfoMetadataAttribute() throws Exception {
        final byte[] original = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(original);
        final byte[] rewritten = this.rewriter.rewrite(index);
        final String html = new String(rewritten, StandardCharsets.UTF_8);

        assertThat(html, containsString("data-dist-info-metadata="));
    }

    @Test
    void filteredOutputExcludesBlockedLinks() throws Exception {
        final byte[] original = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(original);
        final PypiSimpleIndex filtered = this.filter.filter(index, Set.of("2.0.0"));
        final byte[] rewritten = this.rewriter.rewrite(filtered);
        final String html = new String(rewritten, StandardCharsets.UTF_8);

        assertThat(html, not(containsString("my-pkg-2.0.0.tar.gz")));
        assertThat(html, not(containsString("my-pkg-2.0.0-py3-none-any.whl")));
        assertThat(html, containsString("my-pkg-1.0.0.tar.gz"));
    }

    @Test
    void handlesEmptyIndex() throws Exception {
        final String html = "<!DOCTYPE html><html><body></body></html>";
        final PypiSimpleIndex index = this.parser.parse(html.getBytes(StandardCharsets.UTF_8));
        final byte[] rewritten = this.rewriter.rewrite(index);
        final String output = new String(rewritten, StandardCharsets.UTF_8);

        assertThat(output, startsWith("<!DOCTYPE html><html><body>"));
        assertThat(output, containsString("</body></html>"));

        // Round-trip: empty in, empty out
        final PypiSimpleIndex reparsed = this.parser.parse(rewritten);
        assertThat(reparsed.links(), hasSize(0));
    }

    @Test
    void escapesHtmlEntities() throws Exception {
        final String html = """
            <!DOCTYPE html><html><body>
            <a href="pkg-1.0.0.tar.gz#sha256=abc" data-requires-python="&gt;=3.8">pkg-1.0.0.tar.gz</a>
            </body></html>
            """;
        final PypiSimpleIndex index = this.parser.parse(html.getBytes(StandardCharsets.UTF_8));
        final byte[] rewritten = this.rewriter.rewrite(index);
        final String output = new String(rewritten, StandardCharsets.UTF_8);

        // The rewriter should re-escape the requires-python value
        assertThat(output, containsString("data-requires-python=\"&amp;gt;=3.8\""));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.rewriter.contentType(), equalTo("text/html"));
    }

    private static byte[] loadFixture() throws IOException {
        try (InputStream input = PypiMetadataRewriterTest.class.getResourceAsStream(
            "/cooldown/pypi-simple-index-sample.html"
        )) {
            if (input == null) {
                throw new IOException("Fixture not found: /cooldown/pypi-simple-index-sample.html");
            }
            return input.readAllBytes();
        }
    }
}
