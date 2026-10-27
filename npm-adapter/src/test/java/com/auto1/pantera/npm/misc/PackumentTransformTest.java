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
    void extractsOneVersion() throws Exception {
        MatcherAssert.assertThat(
            new PackumentVersion(PackumentTransformTest.chunked(DOC, 4), "1.1.0").manifest().join(),
            new IsEqual<>(Optional.of(MAPPER.readTree(DOC).at("/versions/1.1.0")))
        );
    }

    @Test
    void missingVersionIsEmpty() {
        MatcherAssert.assertThat(
            new PackumentVersion(PackumentTransformTest.chunked(DOC, 4), "9.9.9").manifest().join(),
            new IsEqual<>(Optional.empty())
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
