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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.npm.cooldown.NpmMetadataParser;
import com.auto1.pantera.npm.cooldown.NpmMetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.reactivex.Flowable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link PackumentTransform}, {@link PackumentSkeleton},
 * {@link PackumentDecision} and {@link PackumentVersion}.
 *
 * @since 2.2.11
 */
final class PackumentTransformTest {

    private static final ObjectMapper MAPPER = PackumentJson.MAPPER;

    private static final String DOC = String.join(
        "",
        "{\"_id\":\"pkg\",\"name\":\"pkg\",",
        "\"dist-tags\":{\"latest\":\"1.1.0\",\"beta\":\"1.1.0\",\"old\":\"1.0.0\"},",
        "\"versions\":{",
        "\"1.0.0\":{\"name\":\"pkg\",\"version\":\"1.0.0\",\"n\":1.10,\"big\":123456789012345678901234567890,",
        "\"dist\":{\"tarball\":\"/pkg/-/pkg-1.0.0.tgz\",\"shasum\":\"a\"}},",
        "\"1.1.0\":{\"name\":\"pkg\",\"version\":\"1.1.0\",\"dist\":{\"tarball\":\"/pkg/-/pkg-1.1.0.tgz\"},",
        "\"nested\":{\"versions\":{\"1.1.0\":[1,2,{\"tarball\":\"x\"}]}}}},",
        "\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\",\"1.0.0\":\"2020-01-01T00:00:00.000Z\",",
        "\"1.1.0\":\"2026-10-26T00:00:00.000Z\"},",
        "\"readme\":\"caf\\u00e9 \\\"quoted\\\" \\u2603\",\"arr\":[true,false,null,{\"k\":[]}]}"
    );

    @Test
    void extractsTheSkeleton() throws Exception {
        MatcherAssert.assertThat(
            MAPPER.readTree(new PackumentSkeleton(PackumentTransformTest.chunked(DOC, 5)).bytes().join()),
            new IsEqual<>(
                MAPPER.readTree(
                    String.join(
                        "",
                        "{\"name\":\"pkg\",",
                        "\"dist-tags\":{\"latest\":\"1.1.0\",\"beta\":\"1.1.0\",\"old\":\"1.0.0\"},",
                        "\"versions\":{\"1.0.0\":{},\"1.1.0\":{}},",
                        "\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\",",
                        "\"1.0.0\":\"2020-01-01T00:00:00.000Z\",\"1.1.0\":\"2026-10-26T00:00:00.000Z\"}}"
                    )
                )
            )
        );
    }

    @Test
    void streamsTheFilteredDocument() throws Exception {
        final PackumentDecision decision = PackumentTransformTest.decide(Set.of("1.1.0"), "1.0.0");
        final String text = new String(
            PackumentTransformTest.collect(
                decision.apply(PackumentTransformTest.chunked(DOC, 3), "https://reg.example/npm")
            ),
            StandardCharsets.UTF_8
        );
        final JsonNode served = MAPPER.readTree(text);
        final JsonNode expected = MAPPER.readTree(DOC);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("versions")).remove("1.1.0");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("time")).remove("1.1.0");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).set(
            "dist-tags", MAPPER.readTree("{\"latest\":\"1.0.0\",\"old\":\"1.0.0\"}")
        );
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.at("/versions/1.0.0/dist"))
            .put("tarball", "https://reg.example/npm/pkg/-/pkg-1.0.0.tgz");
        MatcherAssert.assertThat("served document", served, new IsEqual<>(expected));
        MatcherAssert.assertThat(
            "numbers copied exactly",
            text.contains("\"n\":1.10,\"big\":123456789012345678901234567890"),
            new IsEqual<>(true)
        );
    }

    @Test
    void resolvesVersionsAndTagsAgainstSurvivors() throws Exception {
        final PackumentDecision decision = PackumentTransformTest.decide(Set.of("1.1.0"), "1.0.0");
        MatcherAssert.assertThat("literal survivor", decision.resolve("1.0.0"), new IsEqual<>(Optional.of("1.0.0")));
        MatcherAssert.assertThat("blocked literal", decision.resolve("1.1.0"), new IsEqual<>(Optional.empty()));
        MatcherAssert.assertThat("re-pointed latest", decision.resolve("latest"), new IsEqual<>(Optional.of("1.0.0")));
        MatcherAssert.assertThat("dropped tag", decision.resolve("beta"), new IsEqual<>(Optional.empty()));
    }

    @Test
    void handlesDocumentsBeyondJacksonsDefaultLimits() throws Exception {
        final String huge = "x".repeat(21_000_000);
        final String deep = "[".repeat(1500) + "]".repeat(1500);
        final String doc = "{\"name\":\"big\",\"readme\":\"" + huge + "\","
            + "\"dist-tags\":{\"latest\":\"1.0.0\",\"odd\":\"" + huge + "\"},"
            + "\"versions\":{\"1.0.0\":{\"deep\":" + deep + "}},"
            + "\"time\":{\"1.0.0\":\"2020-01-01T00:00:00.000Z\"}}";
        final byte[] skeleton = new PackumentSkeleton(PackumentTransformTest.chunked(doc, 65_536)).bytes().join();
        MatcherAssert.assertThat(
            "cooldown parses the skeleton",
            new NpmMetadataParser().getLatestVersion(new NpmMetadataParser().parse(skeleton)),
            new IsEqual<>(Optional.of("1.0.0"))
        );
        MatcherAssert.assertThat(
            "the filtered stream copies the document",
            MAPPER.readTree(
                PackumentTransformTest.collect(
                    new PackumentDecision(skeleton).apply(PackumentTransformTest.chunked(doc, 65_536), "https://r")
                )
            ).get("readme").asText().length(),
            new IsEqual<>(huge.length())
        );
    }

    @Test
    void skeletonKeepsOnlyShallowStringValues() throws Exception {
        final String deep = "[".repeat(5000) + "]".repeat(5000);
        final String doc = "{\"name\":{\"evil\":" + deep + "},"
            + "\"dist-tags\":{\"latest\":\"1.0.0\",\"evil\":" + deep + ",\"n\":1},"
            + "\"time\":{\"1.0.0\":\"2020-01-01T00:00:00.000Z\",\"evil\":{\"a\":" + deep + "}},"
            + "\"versions\":{\"1.0.0\":{\"deep\":" + deep + "}}}";
        final byte[] skeleton = new PackumentSkeleton(PackumentTransformTest.chunked(doc, 97)).bytes().join();
        MatcherAssert.assertThat(
            "only shallow values survive; a non-string time entry keeps its key",
            MAPPER.readTree(skeleton),
            new IsEqual<>(
                MAPPER.readTree(
                    "{\"dist-tags\":{\"latest\":\"1.0.0\"},"
                        + "\"time\":{\"1.0.0\":\"2020-01-01T00:00:00.000Z\",\"evil\":{}},"
                        + "\"versions\":{\"1.0.0\":{}}}"
                )
            )
        );
        final NpmMetadataFilter filter = new NpmMetadataFilter();
        MatcherAssert.assertThat(
            "cooldown parses, filters and rewrites it",
            new com.auto1.pantera.npm.cooldown.NpmMetadataRewriter().rewrite(
                filter.filter(new NpmMetadataParser().parse(skeleton), Set.of("1.0.0"))
            ).length > 0,
            new IsEqual<>(true)
        );
    }

    @Test
    void nonObjectVersionsAreDropped() throws Exception {
        MatcherAssert.assertThat(
            MAPPER.readTree(
                new PackumentSkeleton(
                    PackumentTransformTest.chunked("{\"versions\":[[[1]]],\"time\":\"x\"}", 3)
                ).bytes().join()
            ),
            new IsEqual<>(MAPPER.readTree("{}"))
        );
    }

    @Test
    void extractsOneVersion() throws Exception {
        final JsonNode expected = MAPPER.readTree(DOC).at("/versions/1.1.0").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("dist"))
            .put("tarball", "https://r/npm/pkg/-/pkg-1.1.0.tgz");
        MatcherAssert.assertThat(
            MAPPER.readTree(
                new PackumentVersion(PackumentTransformTest.chunked(DOC, 4), "1.1.0")
                    .manifest("https://r/npm").join().orElseThrow()
            ),
            new IsEqual<>(expected)
        );
    }

    @Test
    void missingVersionIsEmpty() {
        MatcherAssert.assertThat(
            new PackumentVersion(PackumentTransformTest.chunked(DOC, 4), "9.9.9")
                .manifest("https://r").join().isPresent(),
            new IsEqual<>(false)
        );
    }

    @Test
    void neverEmitsMoreThanAboutAChunkAtATime() throws Exception {
        final StringBuilder doc = new StringBuilder(32 * 1024 * 1024)
            .append("{\"name\":\"big\",\"dist-tags\":{\"latest\":\"0.0.1\"},\"versions\":{");
        final String filler = "x".repeat(4000);
        for (int idx = 0; idx < 5000; ++idx) {
            if (idx > 0) {
                doc.append(',');
            }
            doc.append("\"0.0.").append(idx).append("\":{\"description\":\"").append(filler)
                .append("\",\"dist\":{\"tarball\":\"/big/-/big-0.0.").append(idx).append(".tgz\"}}");
        }
        doc.append("},\"time\":{\"0.0.1\":\"2020-01-01T00:00:00.000Z\"}}");
        final AtomicInteger largest = new AtomicInteger();
        final AtomicInteger total = new AtomicInteger();
        Flowable.fromPublisher(
            PackumentTransformTest.decide(Set.of("0.0.7"), "0.0.1")
                .apply(PackumentTransformTest.chunked(doc.toString(), 8192), "https://r/npm")
        ).blockingForEach(
            buf -> {
                largest.accumulateAndGet(buf.remaining(), Math::max);
                total.addAndGet(buf.remaining());
            }
        );
        MatcherAssert.assertThat(
            "largest emitted buffer " + largest.get() + " of " + total.get() + " bytes",
            largest.get() <= 64 * 1024,
            new IsEqual<>(true)
        );
    }

    @Test
    void numbersAreCopiedAsStored() throws Exception {
        final String doc = "{\"name\":\"n\",\"versions\":{\"1.0.0\":{\"a\":-0.0,\"b\":1e5,\"c\":1.10,"
            + "\"d\":-1E-7,\"e\":123456789012345678901234567890,\"f\":0}},\"x\":[-0.0,2.50E+3]}";
        final String served = new String(
            PackumentTransformTest.collect(
                new PackumentDecision(new PackumentSkeleton(PackumentTransformTest.chunked(doc, 3)).bytes().join())
                    .apply(PackumentTransformTest.chunked(doc, 2), "https://r")
            ),
            StandardCharsets.UTF_8
        );
        MatcherAssert.assertThat(
            "filtered packument",
            served.contains("{\"a\":-0.0,\"b\":1e5,\"c\":1.10,\"d\":-1E-7,\"e\":123456789012345678901234567890,\"f\":0}")
                && served.contains("\"x\":[-0.0,2.50E+3]"),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "version manifest",
            new String(
                new PackumentVersion(PackumentTransformTest.chunked(doc, 2), "1.0.0").manifest("https://r")
                    .join().orElseThrow(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("{\"a\":-0.0,\"b\":1e5,\"c\":1.10,\"d\":-1E-7,\"e\":123456789012345678901234567890,\"f\":0}")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {" {\"name\":\"evil\",\"versions\":{\"6.6.6\":{}}}", " garbage", "]]]", "{"})
    void contentAfterTheRootObjectIsIgnored(final String trailing) throws Exception {
        final String doc = DOC + trailing;
        final byte[] skeleton = new PackumentSkeleton(PackumentTransformTest.chunked(doc, 7)).bytes().join();
        MatcherAssert.assertThat(
            "skeleton is that of the first document",
            skeleton,
            new IsEqual<>(new PackumentSkeleton(PackumentTransformTest.chunked(DOC, 7)).bytes().join())
        );
        final byte[] served = PackumentTransformTest.collect(
            new PackumentDecision(skeleton).apply(PackumentTransformTest.chunked(doc, 5), "https://r")
        );
        MatcherAssert.assertThat(
            "filtered body is one JSON document",
            PackumentTransformTest.rootValues(served),
            new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "and equals the body of the document without trailing content",
            served,
            new IsEqual<>(
                PackumentTransformTest.collect(
                    new PackumentDecision(skeleton).apply(PackumentTransformTest.chunked(DOC, 5), "https://r")
                )
            )
        );
        MatcherAssert.assertThat(
            "manifest still extracted",
            new PackumentVersion(PackumentTransformTest.chunked(doc, 5), "1.0.0").manifest("https://r")
                .join().isPresent(),
            new IsEqual<>(true)
        );
    }

    @Test
    void filteredPathPrefixesTarballsAtEveryDepthLikeTheRawPath() throws Exception {
        final String doc = PackumentTransformTest.pgLike();
        final JsonNode raw = MAPPER.readTree(
            new com.auto1.pantera.http.body.JsonStringRewrite(
                PackumentTransformTest.chunked(doc, 11),
                java.util.Map.of("**/tarball", url -> url.startsWith("/") ? "https://r/npm" + url : url)
            ).asBytesFuture().join()
        );
        final JsonNode filtered = MAPPER.readTree(
            PackumentTransformTest.collect(
                new PackumentDecision(new PackumentSkeleton(PackumentTransformTest.chunked(doc, 11)).bytes().join())
                    .apply(PackumentTransformTest.chunked(doc, 13), "https://r/npm")
            )
        );
        MatcherAssert.assertThat("raw and filtered serve agree", filtered, new IsEqual<>(raw));
        MatcherAssert.assertThat(
            "nested binary tarball is absolute",
            filtered.at("/versions/8.0.0/dist/bin/linux-x64/tarball").asText(),
            new IsEqual<>("https://r/npm/pg/-/pg-native-linux.tgz")
        );
        MatcherAssert.assertThat(
            "root-level tarball is absolute",
            filtered.at("/tarball").asText(),
            new IsEqual<>("https://r/npm/pg/-/root.tgz")
        );
    }

    @Test
    void unpublishedPackumentKeepsItsUnpublishedTimeEntry() throws Exception {
        final String unpublished = "{\"time\":\"2024-02-01T00:00:00.000Z\",\"versions\":[\"1.0.0\",\"1.0.1\"],"
            + "\"maintainers\":[{\"name\":\"a\"}],\"tags\":{\"latest\":\"1.0.1\"}}";
        final String doc = "{\"_id\":\"gone\",\"name\":\"gone\",\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\","
            + "\"modified\":\"2024-02-01T00:00:00.000Z\",\"unpublished\":" + unpublished + "}}";
        final byte[] skeleton = new PackumentSkeleton(PackumentTransformTest.chunked(doc, 6)).bytes().join();
        MatcherAssert.assertThat(
            "the skeleton keeps the key with a stub",
            MAPPER.readTree(skeleton).at("/time/unpublished"),
            new IsEqual<>(MAPPER.readTree("{}"))
        );
        MatcherAssert.assertThat(
            "the stub is no release date",
            new NpmMetadataParser().releaseDates(new NpmMetadataParser().parse(skeleton)).isEmpty(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the served document keeps time.unpublished verbatim",
            new String(
                PackumentTransformTest.collect(
                    new PackumentDecision(skeleton).apply(PackumentTransformTest.chunked(doc, 4), "https://r")
                ),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>(doc)
        );
    }

    @Test
    void withheldVersionLosesItsTimeEntryWhateverItsValue() throws Exception {
        final String doc = "{\"name\":\"p\",\"dist-tags\":{\"latest\":\"1.1.0\"},"
            + "\"versions\":{\"1.0.0\":{},\"1.1.0\":{}},\"time\":{\"1.0.0\":{\"odd\":[1]},"
            + "\"1.1.0\":{\"odd\":[2]},\"2.0.0\":\"2026-10-26T00:00:00.000Z\",\"unpublished\":{\"x\":[[[]]]}}}";
        final NpmMetadataFilter filter = new NpmMetadataFilter();
        final JsonNode skeleton = new NpmMetadataParser().parse(
            new PackumentSkeleton(PackumentTransformTest.chunked(doc, 5)).bytes().join()
        );
        MatcherAssert.assertThat(
            "skeleton stays shallow",
            PackumentTransformTest.depth(skeleton),
            new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "stubs never become versions",
            new NpmMetadataParser().extractVersions(skeleton),
            new IsEqual<>(java.util.List.of("1.0.0", "1.1.0"))
        );
        final JsonNode served = MAPPER.readTree(
            PackumentTransformTest.collect(
                new PackumentDecision(filter.updateLatest(filter.filter(skeleton, Set.of("1.1.0")), "1.0.0"))
                    .apply(PackumentTransformTest.chunked(doc, 3), "https://r")
            )
        );
        MatcherAssert.assertThat(
            "withheld version's time entry is dropped, the others keep their stored value",
            served.get("time"),
            new IsEqual<>(
                MAPPER.readTree(
                    "{\"1.0.0\":{\"odd\":[1]},\"2.0.0\":\"2026-10-26T00:00:00.000Z\",\"unpublished\":{\"x\":[[[]]]}}"
                )
            )
        );
    }

    @Test
    void verdictFingerprintDoesNotDependOnTheExtractedDocument() throws Exception {
        final String full = "{\"name\":\"p\",\"dist-tags\":{\"latest\":\"1.1.0\",\"next\":\"1.1.0\"},"
            + "\"versions\":{\"1.0.0\":{\"x\":1},\"1.1.0\":{\"y\":2}},"
            + "\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\",\"1.0.0\":\"2020-01-01T00:00:00.000Z\","
            + "\"1.1.0\":\"2026-10-26T00:00:00.000Z\",\"modified\":\"2026-10-26T00:00:00.000Z\"}}";
        final String abbreviated = "{\"name\":\"p\",\"modified\":\"2026-10-26T00:00:00.000Z\","
            + "\"time\":{\"modified\":\"2026-10-26T00:00:00.000Z\",\"1.1.0\":\"2026-10-26T00:00:00.000Z\","
            + "\"created\":\"2020-01-01T00:00:00.000Z\",\"1.0.0\":\"2020-01-01T00:00:00.000Z\"},"
            + "\"dist-tags\":{\"next\":\"1.1.0\",\"latest\":\"1.1.0\"},"
            + "\"versions\":{\"1.1.0\":{\"y\":2},\"1.0.0\":{\"x\":1}}}";
        MatcherAssert.assertThat(
            "same verdict from the full and the abbreviated document",
            PackumentTransformTest.fingerprint(abbreviated, Set.of()),
            new IsEqual<>(PackumentTransformTest.fingerprint(full, Set.of()))
        );
        MatcherAssert.assertThat(
            "same verdict with a version withheld",
            PackumentTransformTest.fingerprint(abbreviated, Set.of("1.1.0")),
            new IsEqual<>(PackumentTransformTest.fingerprint(full, Set.of("1.1.0")))
        );
        MatcherAssert.assertThat(
            "different survivors, different fingerprint",
            PackumentTransformTest.fingerprint(full, Set.of("1.1.0"))
                .equals(PackumentTransformTest.fingerprint(full, Set.of())),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a different time entry changes it",
            PackumentTransformTest.fingerprint(full.replace("2020-01-01T00:00:00.000Z\",\"1.1.0", "2020-01-02T00:00:00.000Z\",\"1.1.0"), Set.of())
                .equals(PackumentTransformTest.fingerprint(full, Set.of())),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "a different dist-tag changes it",
            PackumentTransformTest.fingerprint(full.replace("\"next\":\"1.1.0\"", "\"next\":\"1.0.0\""), Set.of())
                .equals(PackumentTransformTest.fingerprint(full, Set.of())),
            new IsEqual<>(false)
        );
    }

    @Test
    void extractsAVersionNestedTooDeepForATree() throws Exception {
        final int levels = 100_000;
        final String doc = "{\"name\":\"deep\",\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{"
            + "\"1.0.0\":{\"name\":\"deep\",\"deep\":" + "[".repeat(levels) + "]".repeat(levels)
            + ",\"dist\":{\"tarball\":\"https://registry.npmjs.org/deep/-/deep-1.0.0.tgz\"}}}}";
        final byte[] manifest = new PackumentVersion(PackumentTransformTest.chunked(doc, 8192), "1.0.0")
            .manifest("https://r/npm").join().orElseThrow();
        MatcherAssert.assertThat("one JSON value", PackumentTransformTest.rootValues(manifest), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "tarball re-rooted",
            new String(manifest, StandardCharsets.UTF_8)
                .endsWith(",\"dist\":{\"tarball\":\"https://r/npm/deep/-/deep-1.0.0.tgz\"}}"),
            new IsEqual<>(true)
        );
    }

    @Test
    void manifestRewritesTarballsLikeThePackumentServe() throws Exception {
        final String doc = PackumentTransformTest.pgLike();
        final JsonNode manifest = MAPPER.readTree(
            new PackumentVersion(PackumentTransformTest.chunked(doc, 9), "8.0.0").manifest("https://r/npm")
                .join().orElseThrow()
        );
        MatcherAssert.assertThat(
            "dist.tarball through Tarballs.rewriteTarball (absolute upstream URL re-rooted)",
            manifest.at("/dist/tarball").asText(),
            new IsEqual<>("https://r/npm/pg/-/pg-8.0.0.tgz")
        );
        MatcherAssert.assertThat(
            "nested relative tarball prefixed",
            manifest.at("/dist/bin/linux-x64/tarball").asText(),
            new IsEqual<>("https://r/npm/pg/-/pg-native-linux.tgz")
        );
        MatcherAssert.assertThat(
            "nested absolute tarball left alone",
            manifest.at("/dist/bin/darwin/tarball").asText(),
            new IsEqual<>("https://cdn.example/pg-darwin.tgz")
        );
    }

    @Test
    void duplicateVersionKeyEmitsTheLastValueOnly() throws Exception {
        final String doc = "{\"versions\":{\"1.0.0\":{\"n\":1,\"deep\":[[[]]]},\"1.0.1\":{},"
            + "\"1.0.0\":{\"n\":2}},\"versions\":{\"1.0.0\":\"odd\",\"1.0.0\":{\"n\":3}}} {\"versions\":{\"1.0.0\":4}}";
        MatcherAssert.assertThat(
            new String(
                new PackumentVersion(PackumentTransformTest.chunked(doc, 3), "1.0.0").manifest("https://r")
                    .join().orElseThrow(),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("{\"n\":3}")
        );
    }

    /**
     * A pg-like packument: per-platform binaries with their own tarballs
     * under {@code dist.bin}, host-stripped by ingest, plus a root-level
     * {@code tarball} and look-alike keys.
     */
    private static String pgLike() {
        return "{\"name\":\"pg\",\"tarball\":\"/pg/-/root.tgz\",\"dist-tags\":{\"latest\":\"8.0.0\"},"
            + "\"versions\":{\"8.0.0\":{\"name\":\"pg\",\"version\":\"8.0.0\","
            + "\"dist\":{\"tarball\":\"https://registry.npmjs.org/pg/-/pg-8.0.0.tgz\",\"bin\":{"
            + "\"linux-x64\":{\"tarball\":\"/pg/-/pg-native-linux.tgz\",\"host\":\"/h\"},"
            + "\"darwin\":{\"tarball\":\"https://cdn.example/pg-darwin.tgz\"}}},"
            + "\"tarballs\":\"/no.tgz\",\"list\":[{\"tarball\":\"/pg/-/a.tgz\"}]},"
            + "\"7.0.0\":{\"dist\":{\"tarball\":\"/pg/-/pg-7.0.0.tgz\"}}},"
            + "\"time\":{\"8.0.0\":\"2020-01-01T00:00:00.000Z\",\"7.0.0\":\"2019-01-01T00:00:00.000Z\"}}";
    }

    private static String fingerprint(final String doc, final Set<String> blocked) throws Exception {
        final NpmMetadataFilter filter = new NpmMetadataFilter();
        final NpmMetadataParser parser = new NpmMetadataParser();
        final JsonNode filtered = filter.filter(
            parser.parse(new PackumentSkeleton(PackumentTransformTest.chunked(doc, 5)).bytes().join()), blocked
        );
        return new PackumentDecision(new com.auto1.pantera.npm.cooldown.NpmMetadataRewriter().rewrite(filtered))
            .fingerprint();
    }

    /**
     * Number of root values in a body, read token by token (no tree, so any
     * depth is fine); fails on malformed JSON.
     */
    private static int rootValues(final byte[] body) throws Exception {
        int roots = 0;
        try (com.fasterxml.jackson.core.JsonParser parser = PackumentJson.FACTORY.createParser(body)) {
            com.fasterxml.jackson.core.JsonToken token = parser.nextToken();
            while (token != null) {
                if (parser.getParsingContext().inRoot()) {
                    ++roots;
                }
                token = parser.nextToken();
            }
        }
        return roots;
    }

    private static int depth(final JsonNode node) {
        int deepest = 0;
        for (final JsonNode child : node) {
            deepest = Math.max(deepest, PackumentTransformTest.depth(child));
        }
        return node.isContainerNode() ? deepest + 1 : 0;
    }

    /**
     * Run the real cooldown filter rules over the skeleton of {@link #DOC}.
     */
    private static PackumentDecision decide(final Set<String> blocked, final String latest)
        throws Exception {
        final NpmMetadataFilter filter = new NpmMetadataFilter();
        final JsonNode skeleton = new NpmMetadataParser().parse(
            new PackumentSkeleton(PackumentTransformTest.chunked(DOC, 64)).bytes().join()
        );
        return new PackumentDecision(
            filter.updateLatest(filter.filter(skeleton, blocked), latest)
        );
    }

    private static Content chunked(final String doc, final int size) {
        final byte[] bytes = doc.getBytes(StandardCharsets.UTF_8);
        return new Content.From(
            Flowable.range(0, (bytes.length + size - 1) / size).map(
                idx -> ByteBuffer.wrap(
                    bytes, idx * size, Math.min(size, bytes.length - idx * size)
                ).slice()
            )
        );
    }

    private static byte[] collect(final Content content) {
        return new SmallJson(content).bytes().join();
    }
}
