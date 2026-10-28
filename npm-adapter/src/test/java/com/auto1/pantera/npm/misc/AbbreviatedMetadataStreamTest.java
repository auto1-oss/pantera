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
package com.auto1.pantera.npm.misc;

import com.auto1.pantera.asto.test.TestResource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;
import javax.json.Json;
import javax.json.JsonObject;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves {@link AbbreviatedMetadataStream} produces the same JSON value, in
 * the same field order, as {@link AbbreviatedMetadata#generate()}, and
 * rejects what the DOM implementation rejects.
 *
 * @since 2.2.11
 */
final class AbbreviatedMetadataStreamTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("documents")
    void isEquivalentToTheDomImplementation(final String label, final String doc)
        throws Exception {
        final JsonObject expected = new AbbreviatedMetadata(
            Json.createReader(new StringReader(doc)).readObject()
        ).generate();
        final JsonObject actual = AbbreviatedMetadataStreamTest.stream(doc);
        MatcherAssert.assertThat(
            label + ": same JSON value",
            actual,
            new IsEqual<>(expected)
        );
        MatcherAssert.assertThat(
            label + ": same top-level field order",
            new ArrayList<>(actual.keySet()),
            new IsEqual<>(new ArrayList<>(expected.keySet()))
        );
        if (expected.containsKey("versions")) {
            final JsonObject versions = expected.getJsonObject("versions");
            MatcherAssert.assertThat(
                label + ": same version order",
                new ArrayList<>(actual.getJsonObject("versions").keySet()),
                new IsEqual<>(new ArrayList<>(versions.keySet()))
            );
            for (final String version : versions.keySet()) {
                MatcherAssert.assertThat(
                    label + ": same field order in " + version,
                    new ArrayList<>(actual.getJsonObject("versions").getJsonObject(version).keySet()),
                    new IsEqual<>(new ArrayList<>(versions.getJsonObject(version).keySet()))
                );
            }
        }
    }

    @Test
    void fallsBackToNowWithoutTimeModified() throws Exception {
        final JsonObject actual = AbbreviatedMetadataStreamTest.stream(
            "{\"name\":\"x\",\"versions\":{}}"
        );
        MatcherAssert.assertThat(
            "modified is present",
            actual.containsKey("modified"),
            new IsEqual<>(true)
        );
        Assertions.assertDoesNotThrow(
            () -> java.time.Instant.parse(actual.getString("modified")),
            "modified is an instant"
        );
    }

    @Test
    void replacesInvalidUtf8InsteadOfFailing() throws Exception {
        final JsonObject actual = AbbreviatedMetadataStreamTest.stream(
            AbbreviatedMetadataStreamTest.bytes(
                "{\"name\":\"x\",\"readme\":\"", new byte[] {(byte) 0xFF, (byte) 0xFE},
                "\",\"versions\":{\"1.0.0\":{\"version\":\"1.0.0\",\"deprecated\":\"a",
                new byte[] {(byte) 0xC3}, "b\"}}}"
            )
        );
        MatcherAssert.assertThat(
            "invalid bytes in a kept field become U+FFFD",
            actual.getJsonObject("versions").getJsonObject("1.0.0").getString("deprecated"),
            new IsEqual<>("a\uFFFDb")
        );
        MatcherAssert.assertThat(
            "invalid bytes in a skipped field do not drop the document",
            actual.getString("name"),
            new IsEqual<>("x")
        );
    }

    @Test
    void replacesLoneSurrogatesInsteadOfFailing() throws Exception {
        final JsonObject actual = AbbreviatedMetadataStreamTest.stream(
            "{\"name\":\"x\",\"versions\":{\"1.0.0\":{\"deprecated\":\"\\ud800x\","
                + "\"description\":\"\\udc00\"}},\"dist-tags\":{\"latest\":\"y\\ud800\"}}"
        );
        MatcherAssert.assertThat(
            "lone surrogate in a version field becomes '?'",
            actual.getJsonObject("versions").getJsonObject("1.0.0").getString("deprecated"),
            new IsEqual<>("?x")
        );
        MatcherAssert.assertThat(
            "lone surrogate at the end of a copied section becomes '?'",
            actual.getJsonObject("dist-tags").getString("latest"),
            new IsEqual<>("y?")
        );
    }

    @Test
    void skipsALeadingByteOrderMark() throws Exception {
        MatcherAssert.assertThat(
            AbbreviatedMetadataStreamTest.stream(
                AbbreviatedMetadataStreamTest.bytes(
                    new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                    "{\"name\":\"bom\",\"versions\":{}}"
                )
            ).getString("name"),
            new IsEqual<>("bom")
        );
    }

    @Test
    void keepsValidNonAsciiTextByteForByte() throws Exception {
        final String doc = "{\"name\":\"\u00e9\u4e2d\",\"time\":{\"modified\":\"m\"},"
            + "\"versions\":{\"1.0.0\":{\"deprecated\":\"\ud83d\ude00 \u00fc\",\"description\":\"\u00e0\",\"version\":\"1.0.0\"}}}";
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        new AbbreviatedMetadataStream(() -> new ByteArrayInputStream(bytes)).writeTo(out);
        MatcherAssert.assertThat(
            out.toString(StandardCharsets.UTF_8),
            new IsEqual<>(
                "{\"name\":\"\u00e9\u4e2d\",\"modified\":\"m\",\"time\":{\"modified\":\"m\"},"
                    + "\"versions\":{\"1.0.0\":{\"version\":\"1.0.0\",\"deprecated\":\"\ud83d\ude00 \u00fc\"}}}"
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void rejectsWhatTheDomImplementationRejects(final String label, final String doc) {
        Assertions.assertThrows(
            Exception.class,
            () -> new AbbreviatedMetadata(
                Json.createReader(new StringReader(doc)).readObject()
            ).generate(),
            label + ": DOM implementation rejects"
        );
        Assertions.assertThrows(
            Exception.class,
            () -> AbbreviatedMetadataStreamTest.stream(doc),
            label + ": streaming implementation rejects"
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void tellsAnInvalidDocumentFromAnUnexpectedShape(final String label, final String doc) {
        final Exception error = Assertions.assertThrows(
            Exception.class, () -> AbbreviatedMetadataStreamTest.stream(doc), label
        );
        MatcherAssert.assertThat(
            label,
            error instanceof com.fasterxml.jackson.core.JsonParseException,
            new IsEqual<>(
                label.startsWith("root") || "truncated".equals(label) || "empty".equals(label)
            )
        );
    }

    @Test
    void reportsASyntaxErrorAfterAShapeProblem() {
        Assertions.assertThrows(
            com.fasterxml.jackson.core.JsonParseException.class,
            () -> AbbreviatedMetadataStreamTest.stream("{\"name\":1,\"versions\":{\"1.0.0\":{\"a\":")
        );
    }

    @Test
    void ignoresContentAfterTheRootObject() throws Exception {
        MatcherAssert.assertThat(
            AbbreviatedMetadataStreamTest.stream(
                "{\"name\":\"x\",\"time\":{\"modified\":\"m\"}} {\"name\":\"y\"} garbage"
            ).getString("name"),
            new IsEqual<>("x")
        );
    }

    /**
     * Documents to compare: label, document.
     *
     * @return Arguments
     */
    static Stream<Arguments> documents() {
        return Stream.of(
            Arguments.of(
                "registry fixture",
                new String(new TestResource("json/original.json").asBytes(), StandardCharsets.UTF_8)
            ),
            Arguments.of(
                "cached fixture",
                new String(new TestResource("json/cached.json").asBytes(), StandardCharsets.UTF_8)
            ),
            Arguments.of(
                "every essential field, shuffled, with noise",
                "{\"versions\":{\"2.0.0\":{\"hasInstallScript\":true,\"readme\":\"long \\\"text\\\" \\u00e9\","
                    + "\"deprecated\":\"use 3\",\"libc\":[\"glibc\"],\"cpu\":[\"x64\"],\"os\":[\"linux\"],"
                    + "\"engines\":{\"node\":\">=18\"},\"bin\":{\"x\":\"bin/x.js\"},"
                    + "\"bundledDependencies\":[\"a\"],\"bundleDependencies\":[\"a\"],"
                    + "\"optionalDependencies\":{\"fsevents\":\"*\"},"
                    + "\"peerDependenciesMeta\":{\"react\":{\"optional\":true}},"
                    + "\"peerDependencies\":{\"react\":\">=16\"},\"devDependencies\":{\"jest\":\"29\"},"
                    + "\"dependencies\":{\"debug\":\"^4\"},\"scripts\":{\"t\":\"x\"},"
                    + "\"dist\":{\"tarball\":\"/x/-/x-2.0.0.tgz\",\"fileCount\":12,\"unpackedSize\":1.5e3,"
                    + "\"signatures\":[{\"keyid\":\"k\",\"sig\":\"s\"}]},\"version\":\"2.0.0\",\"name\":\"x\"},"
                    + "\"1.0.0\":{\"name\":\"x\",\"version\":\"1.0.0\",\"dist\":{\"tarball\":\"/x/-/x-1.0.0.tgz\"},"
                    + "\"engines\":null,\"deprecated\":false}},"
                    + "\"readme\":\"ignored\",\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\","
                    + "\"1.0.0\":\"2020-01-02T00:00:00.000Z\",\"modified\":\"2024-05-05T05:05:05.000Z\"},"
                    + "\"dist-tags\":{\"latest\":\"2.0.0\",\"next\":\"3.0.0-rc.1\"},"
                    + "\"name\":\"x\",\"_id\":\"x\",\"users\":{\"u\":true}}"
            ),
            Arguments.of(
                "numbers of every spelling",
                "{\"name\":\"n\",\"time\":{\"modified\":\"2024-01-01T00:00:00.000Z\"},"
                    + "\"versions\":{\"1.0.0\":{\"version\":\"1.0.0\",\"dist\":{\"a\":0,\"b\":-12,"
                    + "\"c\":1.10,\"d\":1e2,\"e\":-3.5E-7,\"f\":123456789012345678901234567890,"
                    + "\"g\":0.1000000000000000055511151231257827}}}}"
            ),
            Arguments.of(
                "no name, no dist-tags",
                "{\"time\":{\"modified\":\"2024-01-01T00:00:00.000Z\"},\"versions\":{}}"
            ),
            Arguments.of(
                "only a name and a modified time",
                "{\"time\":{\"modified\":\"2024-01-01T00:00:00.000Z\"},\"name\":\"only\"}"
            ),
            Arguments.of(
                "deeply nested essential field",
                "{\"time\":{\"modified\":\"m\"},\"versions\":{\"1\":{\"dist\":"
                    + "{\"a\":[[[{\"b\":[1,[2,{\"c\":null}]]}]]]}}}}"
            )
        );
    }

    /**
     * Documents both implementations reject: label, document.
     *
     * @return Arguments
     */
    static Stream<Arguments> rejected() {
        return Stream.of(
            Arguments.of("root is an array", "[1,2]"),
            Arguments.of("name is a number", "{\"name\":1}"),
            Arguments.of("name is null", "{\"name\":null}"),
            Arguments.of("time is an array", "{\"time\":[]}"),
            Arguments.of("time.modified is a number", "{\"time\":{\"modified\":1}}"),
            Arguments.of("dist-tags is a string", "{\"dist-tags\":\"latest\"}"),
            Arguments.of("versions is an array", "{\"versions\":[]}"),
            Arguments.of("a version is a string", "{\"versions\":{\"1.0.0\":\"x\"}}"),
            Arguments.of("truncated", "{\"name\":\"x\",\"versions\":{\"1.0.0\":{\"name\""),
            Arguments.of("empty", "")
        );
    }

    /**
     * Concatenate text (as UTF-8) and raw byte parts.
     *
     * @param parts Strings and byte arrays
     * @return Bytes
     */
    private static byte[] bytes(final Object... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final Object part : parts) {
            final byte[] chunk;
            if (part instanceof byte[] raw) {
                chunk = raw;
            } else {
                chunk = part.toString().getBytes(StandardCharsets.UTF_8);
            }
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    private static JsonObject stream(final byte[] bytes) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AbbreviatedMetadataStream(() -> new ByteArrayInputStream(bytes)).writeTo(out);
        return Json.createReader(
            new StringReader(out.toString(StandardCharsets.UTF_8))
        ).readObject();
    }

    /**
     * Run the streaming implementation and parse its output.
     *
     * @param doc Full packument
     * @return Abbreviated packument
     * @throws Exception On rejection
     */
    private static JsonObject stream(final String doc) throws Exception {
        final byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AbbreviatedMetadataStream(() -> new ByteArrayInputStream(bytes)).writeTo(out);
        return Json.createReader(
            new StringReader(out.toString(StandardCharsets.UTF_8))
        ).readObject();
    }
}
