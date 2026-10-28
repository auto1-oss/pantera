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

package com.auto1.pantera.http.body;

import com.auto1.pantera.asto.Content;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link JsonStringRewrite}.
 */
final class JsonStringRewriteTest {

    /**
     * A v1-shaped Composer document: versions keyed by name.
     */
    private static final String KEYED = "{\"packages\": {\"acme/api\": {\n"
        + "  \"1.0.0\": {\"name\": \"acme/api\", \"dist\": {\"type\": \"zip\", \"url\": \"old/a.zip\"}},\n"
        + "  \"2.0.0\": {\"dist\": {\"url\": \"old/b.zip\", \"type\": \"zip\"}, \"source\": {\"url\": \"git\"}}\n"
        + "}}, \"url\": \"root\"}";

    /**
     * A v2-shaped Composer document: versions in an array.
     */
    private static final String ARRAY = "{\"packages\":{\"acme/api\":["
        + "{\"dist\":{\"url\":\"old/a.zip\"}},{\"version\":\"2\"},{\"dist\":{\"url\":\"old/b.zip\"}}]}}";

    @Test
    void rewritesOnlyTheSelectedPathsOfKeyedVersions() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(KEYED, "packages/*/*/dist/url", url -> "new/" + url),
            new IsEqual<>(
                KEYED.replace("\"old/a.zip\"", "\"new/old/a.zip\"")
                    .replace("\"old/b.zip\"", "\"new/old/b.zip\"")
            )
        );
    }

    @Test
    void rewritesArrayElementsThroughTheWildcard() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(ARRAY, "packages/*/*/dist/url", url -> "new/" + url),
            new IsEqual<>(ARRAY.replace("\"old/", "\"new/old/"))
        );
    }

    @Test
    void rootLevelRuleDoesNotTouchNestedKeysOfTheSameName() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(KEYED, "url", url -> "ROOT"),
            new IsEqual<>(KEYED.replace("\"url\": \"root\"", "\"url\": \"ROOT\""))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64})
    void resultDoesNotDependOnChunkBoundaries(final int chunk) {
        final String expected = JsonStringRewriteTest.rewrite(KEYED, "packages/*/*/dist/url", url -> "new/" + url);
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(KEYED, chunk, "packages/*/*/dist/url", url -> "new/" + url),
            new IsEqual<>(expected)
        );
    }

    /**
     * A pg-like npm packument: tarballs at the root, under {@code dist},
     * under {@code dist.bin.*} (an object) and in an array, plus look-alike
     * keys that must not be selected.
     */
    private static final String NESTED = "{\"tarball\":\"/root.tgz\",\"versions\":{\"1.0.0\":{"
        + "\"dist\":{\"tarball\":\"/pg/-/pg-1.0.0.tgz\",\"bin\":{\"linux\":{\"tarball\":\"/bin/l.tgz\","
        + "\"host\":\"/h\"}}},\"tarballs\":\"/no.tgz\",\"xtarball\":\"/no.tgz\","
        + "\"list\":[{\"tarball\":\"/a.tgz\"},[{\"tarball\":\"/b.tgz\"}],\"/no.tgz\"],"
        + "\"tarball\":{\"nested\":\"/no.tgz\"},\"arr\":{\"tarball\":[\"/no.tgz\"]}}}}";

    @Test
    void deepRuleSelectsTheKeyAtEveryDepth() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(NESTED, "**/tarball", url -> "P" + url),
            new IsEqual<>(
                NESTED.replace("\"/root.tgz\"", "\"P/root.tgz\"")
                    .replace("\"/pg/-/pg-1.0.0.tgz\"", "\"P/pg/-/pg-1.0.0.tgz\"")
                    .replace("\"/bin/l.tgz\"", "\"P/bin/l.tgz\"")
                    .replace("\"/a.tgz\"", "\"P/a.tgz\"")
                    .replace("\"/b.tgz\"", "\"P/b.tgz\"")
            )
        );
    }

    @Test
    void deepRuleMatchesALongerSuffix() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(NESTED, "**/bin/*/tarball", url -> "P" + url),
            new IsEqual<>(NESTED.replace("\"/bin/l.tgz\"", "\"P/bin/l.tgz\""))
        );
    }

    @Test
    void deepRuleSelectsARootMember() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite("{\"tarball\":\"/x\",\"k\":\"/y\"}", "**/tarball", url -> "P" + url),
            new IsEqual<>("{\"tarball\":\"P/x\",\"k\":\"/y\"}")
        );
    }

    @Test
    void deepRuleSelectsNothingWithoutTheKey() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(KEYED, "**/tarball", url -> "P" + url),
            new IsEqual<>(KEYED)
        );
    }

    @Test
    void exactRuleStillSelectsOnlyItsPath() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(NESTED, "versions/*/dist/tarball", url -> "P" + url),
            new IsEqual<>(NESTED.replace("\"/pg/-/pg-1.0.0.tgz\"", "\"P/pg/-/pg-1.0.0.tgz\""))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64})
    void deepRuleDoesNotDependOnChunkBoundaries(final int chunk) {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(NESTED, chunk, "**/tarball", url -> "P" + url),
            new IsEqual<>(JsonStringRewriteTest.rewrite(NESTED, "**/tarball", url -> "P" + url))
        );
    }

    @Test
    void unchangedValueKeepsItsOriginalEscaping() {
        final String stored = "{\"dist\":{\"url\":\"a\\/b\\u0041\"}}";
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(stored, "dist/url", UnaryOperator.identity()),
            new IsEqual<>(stored)
        );
    }

    @Test
    void selectedValueIsDecodedBeforeTheRuleSeesIt() {
        final List<String> seen = new ArrayList<>(1);
        JsonStringRewriteTest.rewrite(
            "{\"dist\":{\"url\":\"a\\/b\\u0041\\\"q\\\\\"}}", "dist/url",
            url -> {
                seen.add(url);
                return url;
            }
        );
        MatcherAssert.assertThat(seen, new IsEqual<>(List.of("a/bA\"q\\")));
    }

    @Test
    void replacementIsEscaped() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite("{\"k\":\"v\"}", "k", url -> "q\"b\\s\n"),
            new IsEqual<>("{\"k\":\"q\\\"b\\\\s\\n\"}")
        );
    }

    @Test
    void escapedQuoteInsideAKeyDoesNotEndTheKey() {
        final String stored = "{\"a\\\"k\":{\"url\":\"x\"},\"k\":{\"url\":\"y\"}}";
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(stored, "k/url", url -> "Y"),
            new IsEqual<>("{\"a\\\"k\":{\"url\":\"x\"},\"k\":{\"url\":\"Y\"}}")
        );
    }

    @Test
    void stringsThatLookLikeStructureAreNotStructure() {
        final String stored = "{\"noise\":\"{[,:\\\"\",\"k\":\"v\",\"n\":[1,true,null,{\"k\":\"deep\"}]}";
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(stored, "k", url -> "V"),
            new IsEqual<>(stored.replace("\"k\":\"v\"", "\"k\":\"V\""))
        );
    }

    @Test
    void nonAsciiBytesPassThrough() {
        final String stored = "{\"name\":\"Ünïcødé ☃\",\"k\":\"ß\"}";
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(stored, 1, "k", url -> url + "!"),
            new IsEqual<>("{\"name\":\"Ünïcødé ☃\",\"k\":\"ß!\"}")
        );
    }

    @Test
    void malformedDocumentIsPassedThroughAsStored() {
        final String stored = "{\"k\":\"unterminated";
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite(stored, 5, "k", url -> "X"),
            new IsEqual<>(stored)
        );
    }

    @Test
    void notJsonAtAllIsPassedThroughAsStored() {
        MatcherAssert.assertThat(
            JsonStringRewriteTest.rewrite("not json", 3, "k", url -> "X"),
            new IsEqual<>("not json")
        );
    }

    @Test
    void ruleIsInvokedOncePerSelectedValue() {
        final AtomicInteger calls = new AtomicInteger();
        JsonStringRewriteTest.rewrite(
            KEYED, 4, "packages/*/*/dist/url",
            url -> {
                calls.incrementAndGet();
                return url;
            }
        );
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(2));
    }

    @Test
    void sizeIsUnknownAfterRewrite() {
        MatcherAssert.assertThat(
            new JsonStringRewrite(
                new Content.From(KEYED.getBytes(StandardCharsets.UTF_8)), Map.of("k", UnaryOperator.identity())
            ).size().isEmpty(),
            new IsEqual<>(true)
        );
    }

    /**
     * Rewrite a document delivered as one chunk.
     *
     * @param stored Document
     * @param path Rule path
     * @param fn Rule
     * @return Result
     */
    private static String rewrite(final String stored, final String path, final UnaryOperator<String> fn) {
        return JsonStringRewriteTest.rewrite(stored, Integer.MAX_VALUE, path, fn);
    }

    /**
     * Rewrite a document delivered in chunks of a given size.
     *
     * @param stored Document
     * @param chunk Chunk size in bytes
     * @param path Rule path
     * @param fn Rule
     * @return Result
     */
    private static String rewrite(
        final String stored, final int chunk, final String path, final UnaryOperator<String> fn
    ) {
        final byte[] bytes = stored.getBytes(StandardCharsets.UTF_8);
        final List<ByteBuffer> chunks = new ArrayList<>();
        for (int from = 0; from < bytes.length; from += chunk) {
            chunks.add(
                ByteBuffer.wrap(bytes, from, Math.min(chunk, bytes.length - from)).slice()
            );
        }
        return new String(
            new JsonStringRewrite(new Content.From(Flowable.fromIterable(chunks)), Map.of(path, fn))
                .asBytesFuture().join(),
            StandardCharsets.UTF_8
        );
    }
}
