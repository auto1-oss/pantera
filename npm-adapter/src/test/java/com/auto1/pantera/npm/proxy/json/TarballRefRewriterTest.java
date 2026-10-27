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
package com.auto1.pantera.npm.proxy.json;

import com.auto1.pantera.asto.test.TestResource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves {@link TarballRefRewriter} produces exactly the bytes of the String
 * implementation it replaces on the ingest path,
 * {@code new CachedContent(doc, pkg).valueString()}, whatever the chunking.
 *
 * @since 2.2.11
 */
final class TarballRefRewriterTest {

    /**
     * Chunk sizes every case is fed with: single bytes (every state change
     * straddles a boundary), awkward primes, and the whole document.
     */
    private static final int[] CHUNKS = {1, 2, 3, 7, 13, 64, 1021, Integer.MAX_VALUE};

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesTheStringImplementationByteForByte(
        final String label, final String pkg, final String doc
    ) throws IOException {
        final byte[] expected = new CachedContent(doc, pkg).valueString()
            .getBytes(StandardCharsets.UTF_8);
        for (final int chunk : TarballRefRewriterTest.CHUNKS) {
            MatcherAssert.assertThat(
                String.format("%s at chunk size %d", label, chunk),
                new String(
                    TarballRefRewriterTest.rewrite(pkg, doc.getBytes(StandardCharsets.UTF_8), chunk),
                    StandardCharsets.UTF_8
                ),
                new IsEqual<>(new String(expected, StandardCharsets.UTF_8))
            );
        }
    }

    @Test
    void matchesOnTheRegistryFixture() throws IOException {
        final byte[] doc = new TestResource("json/original.json").asBytes();
        final String text = new String(doc, StandardCharsets.UTF_8);
        MatcherAssert.assertThat(
            new String(TarballRefRewriterTest.rewrite("asdas", doc, 4096), StandardCharsets.UTF_8),
            new IsEqual<>(new CachedContent(text, "asdas").valueString())
        );
    }

    @Test
    void matchesOnALargeRealisticScopedPackument() throws IOException {
        final String doc = TarballRefRewriterTest.realistic("@prisma/client", 400);
        final byte[] expected = new CachedContent(doc, "@prisma/client").valueString()
            .getBytes(StandardCharsets.UTF_8);
        for (final int chunk : new int[] {1, 17, 8192, 65_536}) {
            MatcherAssert.assertThat(
                String.format("chunk %d", chunk),
                TarballRefRewriterTest.rewrite(
                    "@prisma/client", doc.getBytes(StandardCharsets.UTF_8), chunk
                ),
                new IsEqual<>(expected)
            );
        }
    }

    @Test
    void rewritesAbsoluteScopedTarball() throws IOException {
        MatcherAssert.assertThat(
            new String(
                TarballRefRewriterTest.rewrite(
                    "@scope/pkg",
                    "{\"dist\":{\"tarball\":\"https://registry.npmjs.org/@scope/pkg/-/pkg-1.0.0.tgz\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                    5
                ),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("{\"dist\":{\"tarball\":\"/@scope/pkg/-/pkg-1.0.0.tgz\"}}")
        );
    }

    /**
     * Cases: label, package, document.
     *
     * @return Arguments
     */
    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of(
                "absolute tarball", "lodash",
                "{\"versions\":{\"1.0.0\":{\"dist\":{\"tarball\":\"https://registry.npmjs.org/lodash/-/lodash-1.0.0.tgz\"}}}}"
            ),
            Arguments.of(
                "scoped tarball", "@scope/pkg",
                "{\"versions\":{\"1.0.0\":{\"dist\":{\"tarball\":\"https://registry.npmjs.org/@scope/pkg/-/pkg-1.0.0.tgz\",\"shasum\":\"abc\"}}}}"
            ),
            Arguments.of(
                "escaped slashes stay as they are", "lodash",
                "{\"dist\":{\"tarball\":\"https:\\/\\/registry.npmjs.org\\/lodash\\/-\\/lodash-1.0.0.tgz\"}}"
            ),
            Arguments.of(
                "already relative stays", "lodash",
                "{\"dist\":{\"tarball\":\"/lodash/-/lodash-1.0.0.tgz\"}}"
            ),
            Arguments.of(
                "host-relative with prefix", "lodash",
                "{\"dist\":{\"tarball\":\"/prefix/lodash/-/lodash-1.0.0.tgz\"}}"
            ),
            Arguments.of(
                "foreign package path stays", "lodash",
                "{\"dist\":{\"tarball\":\"https://registry.npmjs.org/underscore/-/underscore-1.0.0.tgz\"}}"
            ),
            Arguments.of(
                "whitespace around the colon", "lodash",
                "{\n  \"dist\" : {\n    \"tarball\"  :\t\r\n \"https://r.example/lodash/-/lodash-1.0.0.tgz\"\n  }\n}"
            ),
            Arguments.of(
                "tarball key outside dist", "lodash",
                "{\"tarball\":\"https://r.example/lodash/-/lodash-2.0.0.tgz\",\"versions\":{}}"
            ),
            Arguments.of(
                "tarball as a value is not a key", "lodash",
                "{\"keywords\":[\"tarball\",\"https://r.example/lodash/-/x.tgz\"],\"k\":\"tarball\"}"
            ),
            Arguments.of(
                "tarball text inside a readme", "lodash",
                "{\"readme\":\"use \\\"tarball\\\": \\\"https://r.example/lodash/-/lodash-1.0.0.tgz\\\" here\"}"
            ),
            Arguments.of(
                "non-string tarball value", "lodash",
                "{\"tarball\":{\"url\":\"https://r.example/lodash/-/a.tgz\"},\"t\":[1,{\"tarball\":null}]}"
            ),
            Arguments.of(
                "empty tarball value", "lodash",
                "{\"dist\":{\"tarball\":\"\"},\"next\":{\"tarball\":\"https://r.example/lodash/-/b.tgz\"}}"
            ),
            Arguments.of(
                "escaped quote inside the tarball value", "lodash",
                "{\"dist\":{\"tarball\":\"https://r.example/lodash/-/a\\\"b/lodash/-/c.tgz\"}}"
            ),
            Arguments.of(
                "key ending in an escaped quote and tarball", "lodash",
                "{\"odd\\\"tarball\":\"https://r.example/lodash/-/odd.tgz\"}"
            ),
            Arguments.of(
                "longer key ending in tarball", "lodash",
                "{\"mytarball\":\"https://r.example/lodash/-/mine.tgz\"}"
            ),
            Arguments.of(
                "escaped backslash before the closing quote", "lodash",
                "{\"a\":\"x\\\\\",\"dist\":{\"tarball\":\"https://r.example/lodash/-/lodash-1.0.0.tgz\"}}"
            ),
            Arguments.of(
                "non-ascii text and tarball", "lodash",
                "{\"description\":\"Größe – 日本語 – emoji \uD83D\uDE80\",\"dist\":{\"tarball\":\"https://r.example/lodash/-/lodash-1.0.0-ß.tgz\"}}"
            ),
            Arguments.of(
                "regex metacharacters in package name", "a.b+c",
                "{\"dist\":{\"tarball\":\"https://r.example/a.b+c/-/a.b+c-1.0.0.tgz\"},\"x\":{\"tarball\":\"https://r.example/aXb+c/-/q.tgz\"}}"
            ),
            Arguments.of(
                "truncated inside a tarball value", "lodash",
                "{\"dist\":{\"tarball\":\"https://r.example/lodash/-/lodash-1.0"
            ),
            Arguments.of(
                "empty document", "lodash", ""
            )
        );
    }

    /**
     * Rewrite a document fed in chunks.
     *
     * @param pkg Package
     * @param doc Document bytes
     * @param chunk Chunk size
     * @return Rewritten bytes
     * @throws IOException Never for an in-memory sink
     */
    private static byte[] rewrite(final String pkg, final byte[] doc, final int chunk)
        throws IOException {
        final TarballRefRewriter rewriter = new TarballRefRewriter(pkg);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int pos = 0; pos < doc.length; pos += Math.min(chunk, doc.length - pos)) {
            final int len = Math.min(chunk, doc.length - pos);
            // A view with a non-zero offset/position, as upstream chunks are.
            final ByteBuffer buf = ByteBuffer.allocate(len + 3);
            buf.position(3);
            buf.put(doc, pos, len);
            buf.position(3);
            rewriter.write(buf, out);
        }
        rewriter.finish(out);
        return out.toByteArray();
    }

    /**
     * Registry-shaped packument: many versions with absolute tarballs,
     * dependencies, a few already-relative and escaped tarballs, a readme with
     * escapes and non-ASCII text, and a time map.
     *
     * @param pkg Package name
     * @param versions Number of versions
     * @return Document
     */
    private static String realistic(final String pkg, final int versions) {
        final String base = pkg.substring(pkg.lastIndexOf('/') + 1);
        final StringBuilder doc = new StringBuilder(versions * 600)
            .append("{\"_id\":\"").append(pkg).append("\",\"_rev\":\"12-abc\",\"name\":\"")
            .append(pkg).append("\",\"dist-tags\":{\"latest\":\"1.0.").append(versions - 1)
            .append("\"},\"versions\":{");
        for (int idx = 0; idx < versions; idx += 1) {
            if (idx > 0) {
                doc.append(',');
            }
            final String tarball;
            if (idx % 50 == 7) {
                tarball = String.format("/%s/-/%s-1.0.%d.tgz", pkg, base, idx);
            } else if (idx % 50 == 13) {
                tarball = String.format(
                    "https:\\/\\/registry.npmjs.org\\/%s\\/-\\/%s-1.0.%d.tgz",
                    pkg.replace("/", "\\/"), base, idx
                );
            } else {
                tarball = String.format(
                    "https://registry.npmjs.org/%s/-/%s-1.0.%d.tgz", pkg, base, idx
                );
            }
            doc.append(String.format(
                "\"1.0.%d\":{\"name\":\"%s\",\"version\":\"1.0.%d\",\"description\":\"Build \\\"%d\\\" \\u00e9\","
                    + "\"dependencies\":{\"debug\":\"^4.%d.0\"},\"scripts\":{\"test\":\"echo \\\"tarball\\\": x\"},"
                    + "\"dist\":{\"shasum\":\"%040d\",\"integrity\":\"sha512-%d==\",\"tarball\" : \"%s\"},"
                    + "\"_npmOperationalInternal\":{\"host\":\"s3://npm-registry-packages\"}}",
                idx, pkg, idx, idx, idx % 9, idx, idx, tarball
            ));
        }
        doc.append("},\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\"");
        for (int idx = 0; idx < versions; idx += 1) {
            doc.append(String.format(",\"1.0.%d\":\"2021-01-01T00:00:%02d.000Z\"", idx, idx % 60));
        }
        return doc.append(
            "},\"readme\":\"# Client\\n\\nGrüße – \\\"quoted\\\" \\\\ backslash, tarball: inline\\n\","
                + "\"license\":\"Apache-2.0\"}"
        ).toString();
    }
}
