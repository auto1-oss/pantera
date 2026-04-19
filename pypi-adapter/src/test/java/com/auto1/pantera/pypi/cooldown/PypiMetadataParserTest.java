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

import com.auto1.pantera.cooldown.metadata.MetadataParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link PypiMetadataParser}.
 *
 * @since 2.2.0
 */
final class PypiMetadataParserTest {

    private PypiMetadataParser parser;

    @BeforeEach
    void setUp() {
        this.parser = new PypiMetadataParser();
    }

    @Test
    void parsesFixtureFile() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);

        assertThat(index, is(notNullValue()));
        assertThat(index.links(), hasSize(10));
    }

    @Test
    void extractsVersionsFromFixture() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);
        final List<String> versions = this.parser.extractVersions(index);

        assertThat(versions, hasSize(9));
        assertThat(versions, containsInAnyOrder(
            "0.9.0", "1.0.0", "1.1.0", "1.2.0", "1.3.0",
            "2.0.0", "2.1.0a1", "2.1.0", "3.0.0rc1"
        ));
    }

    @Test
    void deduplicatesVersionsAcrossFileTypes() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);
        final List<String> versions = this.parser.extractVersions(index);

        // Version 2.0.0 appears twice (tar.gz + whl) — should only appear once
        final long count = versions.stream().filter("2.0.0"::equals).count();
        assertThat("Version 2.0.0 should appear only once", count, equalTo(1L));
    }

    @Test
    void parsesLinkHrefs() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);
        final PypiSimpleIndex.Link first = index.links().get(0);

        assertThat(first.href(), equalTo("../../packages/my-pkg-0.9.0.tar.gz#sha256=aaa111"));
        assertThat(first.filename(), equalTo("my-pkg-0.9.0.tar.gz"));
    }

    @Test
    void parsesRequiresPythonAttribute() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);

        // Third link (index 2) has data-requires-python
        final PypiSimpleIndex.Link link = index.links().get(2);
        assertThat(link.filename(), equalTo("my-pkg-1.1.0-py3-none-any.whl"));
        assertThat(link.requiresPython(), equalTo("&gt;=3.8"));
    }

    @Test
    void parsesDistInfoMetadataAttribute() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);

        // Fifth link (index 4) has data-dist-info-metadata
        final PypiSimpleIndex.Link link = index.links().get(4);
        assertThat(link.filename(), equalTo("my_pkg-1.3.0-py3-none-any.whl"));
        assertThat(link.distInfoMetadata(), equalTo("sha256=meta1"));
    }

    @Test
    void parsesPreReleaseVersions() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);
        final List<String> versions = this.parser.extractVersions(index);

        assertThat("Should include alpha pre-release", versions.contains("2.1.0a1"), is(true));
        assertThat("Should include rc pre-release", versions.contains("3.0.0rc1"), is(true));
    }

    @Test
    void parsesMinimalHtml() throws Exception {
        final String html = """
            <!DOCTYPE html><html><body>
            <a href="pkg-1.0.0.tar.gz#sha256=abc">pkg-1.0.0.tar.gz</a>
            </body></html>
            """;
        final PypiSimpleIndex index = this.parser.parse(html.getBytes(StandardCharsets.UTF_8));

        assertThat(index.links(), hasSize(1));
        assertThat(index.links().get(0).version(), equalTo("1.0.0"));
    }

    @Test
    void returnsEmptyVersionsForEmptyBody() throws Exception {
        final String html = "<!DOCTYPE html><html><body></body></html>";
        final PypiSimpleIndex index = this.parser.parse(html.getBytes(StandardCharsets.UTF_8));

        assertThat(index.links(), is(empty()));
        assertThat(this.parser.extractVersions(index), is(empty()));
    }

    @Test
    void getLatestVersionReturnsEmpty() throws Exception {
        final byte[] html = loadFixture();
        final PypiSimpleIndex index = this.parser.parse(html);
        final Optional<String> latest = this.parser.getLatestVersion(index);

        assertThat("PyPI Simple Index has no latest tag", latest.isPresent(), is(false));
    }

    @Test
    void returnsCorrectContentType() {
        assertThat(this.parser.contentType(), equalTo("text/html"));
    }

    @Test
    void handlesNullMetadata() {
        assertThat(this.parser.extractVersions(null), is(empty()));
    }

    @ParameterizedTest
    @CsvSource({
        "my-pkg-1.0.0.tar.gz,                    1.0.0",
        "my-pkg-1.2.3.zip,                        1.2.3",
        "my_pkg-2.0.0-py3-none-any.whl,           2.0.0",
        "my_pkg-0.1-py2.py3-none-any.whl,         0.1",
        "my-pkg-1.0.0a1.tar.gz,                   1.0.0a1",
        "my-pkg-1.0.0rc2.tar.gz,                  1.0.0rc2",
        "my-pkg-1.0.0b3.tar.gz,                   1.0.0b3",
        "my-pkg-1.0.0.post1.tar.gz,               1.0.0.post1",
        "my-pkg-1.0.0.dev4.tar.gz,                1.0.0.dev4",
        "numpy-1.26.4-cp312-cp312-manylinux_2_17_x86_64.manylinux2014_x86_64.whl, 1.26.4",
        "Django-4.2.11.tar.gz,                     4.2.11",
        "my-pkg-2024.1.15.tar.gz,                  2024.1.15",
    })
    void extractsVersionFromFilename(final String filename, final String expected) {
        final String actual = PypiMetadataParser.extractVersionFromFilename(filename);
        assertThat(actual, equalTo(expected));
    }

    @Test
    void extractVersionReturnsNullForUnparseableFilename() {
        assertThat(PypiMetadataParser.extractVersionFromFilename("README.md"), is(nullValue()));
        assertThat(PypiMetadataParser.extractVersionFromFilename(""), is(nullValue()));
        assertThat(PypiMetadataParser.extractVersionFromFilename(null), is(nullValue()));
    }

    @Test
    void handlesLargeIndex() throws Exception {
        final StringBuilder html = new StringBuilder("<!DOCTYPE html><html><body>\n");
        for (int idx = 0; idx < 500; idx++) {
            html.append(String.format(
                "<a href=\"packages/pkg-%d.0.0.tar.gz#sha256=hash%d\">pkg-%d.0.0.tar.gz</a>\n",
                idx, idx, idx
            ));
        }
        html.append("</body></html>");

        final PypiSimpleIndex index = this.parser.parse(
            html.toString().getBytes(StandardCharsets.UTF_8)
        );
        assertThat(index.links(), hasSize(500));

        final List<String> versions = this.parser.extractVersions(index);
        assertThat(versions, hasSize(500));
    }

    private static byte[] loadFixture() throws IOException {
        try (InputStream input = PypiMetadataParserTest.class.getResourceAsStream(
            "/cooldown/pypi-simple-index-sample.html"
        )) {
            if (input == null) {
                throw new IOException("Fixture not found: /cooldown/pypi-simple-index-sample.html");
            }
            return input.readAllBytes();
        }
    }
}
