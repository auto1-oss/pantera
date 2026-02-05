/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.pypi.meta;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PypiMetadataMerger}.
 *
 * @since 1.18.0
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class PypiMetadataMergerTest {

    /**
     * Merger under test.
     */
    private PypiMetadataMerger merger;

    @BeforeEach
    void setUp() {
        this.merger = new PypiMetadataMerger();
    }

    @Test
    void mergesLinksFromMultipleMembers() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", member1Html());
        responses.put("member2", member2Html());
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("pkg-1.0.0.tar.gz")
        );
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("pkg-2.0.0.tar.gz")
        );
    }

    @Test
    void deduplicatesByFilename() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", duplicateHtml1());
        responses.put("member2", duplicateHtml2());
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        // Count anchor tags with the filename (each anchor has filename twice: href and text)
        final long count = countOccurrences(html, "<a href=");
        MatcherAssert.assertThat(
            "There should be exactly one anchor tag for pkg-1.0.0.tar.gz",
            count,
            Matchers.equalTo(1L)
        );
    }

    @Test
    void sortsAlphabetically() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", unsortedHtml());
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        final int pos1 = html.indexOf("a-pkg-1.0.0.tar.gz");
        final int pos2 = html.indexOf("b-pkg-1.0.0.tar.gz");
        final int pos3 = html.indexOf("c-pkg-1.0.0.tar.gz");
        MatcherAssert.assertThat(
            "Links should be sorted alphabetically",
            pos1 < pos2 && pos2 < pos3,
            Matchers.equalTo(true)
        );
    }

    @Test
    void handlesEmptyResponses() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("<!DOCTYPE html>")
        );
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("<html>")
        );
    }

    @Test
    void handlesSingleResponse() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", member1Html());
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("pkg-1.0.0.tar.gz")
        );
    }

    @Test
    void generatesValidHtmlStructure() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", member1Html());
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("<!DOCTYPE html>")
        );
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("<html>")
        );
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("</html>")
        );
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("<body>")
        );
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("</body>")
        );
    }

    @Test
    void preservesHashInHref() {
        final LinkedHashMap<String, byte[]> responses = new LinkedHashMap<>();
        responses.put("member1", htmlWithHash());
        final byte[] result = this.merger.merge(responses);
        final String html = new String(result, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            html,
            Matchers.containsString("#sha256=abc123")
        );
    }

    private static byte[] member1Html() {
        return ("<!DOCTYPE html><html><body>\n"
            + "<a href=\"/pkg/pkg-1.0.0.tar.gz\">pkg-1.0.0.tar.gz</a>\n"
            + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] member2Html() {
        return ("<!DOCTYPE html><html><body>\n"
            + "<a href=\"/pkg/pkg-2.0.0.tar.gz\">pkg-2.0.0.tar.gz</a>\n"
            + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] duplicateHtml1() {
        return ("<!DOCTYPE html><html><body>\n"
            + "<a href=\"/member1/pkg-1.0.0.tar.gz\">pkg-1.0.0.tar.gz</a>\n"
            + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] duplicateHtml2() {
        return ("<!DOCTYPE html><html><body>\n"
            + "<a href=\"/member2/pkg-1.0.0.tar.gz\">pkg-1.0.0.tar.gz</a>\n"
            + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] unsortedHtml() {
        return ("<!DOCTYPE html><html><body>\n"
            + "<a href=\"/pkg/c-pkg-1.0.0.tar.gz\">c-pkg-1.0.0.tar.gz</a>\n"
            + "<a href=\"/pkg/a-pkg-1.0.0.tar.gz\">a-pkg-1.0.0.tar.gz</a>\n"
            + "<a href=\"/pkg/b-pkg-1.0.0.tar.gz\">b-pkg-1.0.0.tar.gz</a>\n"
            + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] htmlWithHash() {
        return ("<!DOCTYPE html><html><body>\n"
            + "<a href=\"/pkg/pkg-1.0.0.tar.gz#sha256=abc123\">pkg-1.0.0.tar.gz</a>\n"
            + "</body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private static long countOccurrences(final String text, final String substring) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) != -1) {
            count++;
            idx += substring.length();
        }
        return count;
    }
}
