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
package com.auto1.pantera.group.merge;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link StreamingMetadataMerger}.
 */
final class StreamingMetadataMergerTest {

    @Test
    void mergesDisjointVersionSets() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<groupId>com.example</groupId><artifactId>lib</artifactId>"
                + "<versioning><versions>"
                + "<version>1.0</version><version>1.1</version>"
                + "</versions></versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<groupId>com.example</groupId><artifactId>lib</artifactId>"
                + "<versioning><versions>"
                + "<version>2.0</version><version>2.1</version>"
                + "</versions></versioning></metadata>"
        ));
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(out, Matchers.containsString("<version>1.0</version>"));
        MatcherAssert.assertThat(out, Matchers.containsString("<version>1.1</version>"));
        MatcherAssert.assertThat(out, Matchers.containsString("<version>2.0</version>"));
        MatcherAssert.assertThat(out, Matchers.containsString("<version>2.1</version>"));
        MatcherAssert.assertThat(merger.membersMerged(), Matchers.equalTo(2));
        MatcherAssert.assertThat(merger.membersSkipped(), Matchers.equalTo(0));
    }

    @Test
    void deduplicatesOverlappingVersions() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><versions>"
                + "<version>1.0</version><version>1.1</version><version>2.0</version>"
                + "</versions></versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><versions>"
                + "<version>1.1</version><version>2.0</version><version>2.1</version>"
                + "</versions></versioning></metadata>"
        ));
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(
            "Each version listed exactly once",
            countOccurrences(out, "<version>1.1</version>"),
            Matchers.equalTo(1)
        );
        MatcherAssert.assertThat(
            countOccurrences(out, "<version>2.0</version>"),
            Matchers.equalTo(1)
        );
        MatcherAssert.assertThat(
            countOccurrences(out, "<version>1.0</version>"),
            Matchers.equalTo(1)
        );
        MatcherAssert.assertThat(
            countOccurrences(out, "<version>2.1</version>"),
            Matchers.equalTo(1)
        );
    }

    @Test
    void lastUpdatedKeepsMaxAcrossMembers() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning>"
                + "<versions><version>1.0</version></versions>"
                + "<lastUpdated>20200101120000</lastUpdated>"
                + "</versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning>"
                + "<versions><version>1.0</version></versions>"
                + "<lastUpdated>20240601090000</lastUpdated>"
                + "</versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning>"
                + "<versions><version>1.0</version></versions>"
                + "<lastUpdated>20210101000000</lastUpdated>"
                + "</versioning></metadata>"
        ));
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(
            "Newest lastUpdated wins",
            out,
            Matchers.containsString("<lastUpdated>20240601090000</lastUpdated>")
        );
        MatcherAssert.assertThat(
            "Older lastUpdated values are dropped",
            out,
            Matchers.not(Matchers.containsString("20200101120000"))
        );
    }

    @Test
    void snapshotKeepsNewestTimestamp() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><snapshot>"
                + "<timestamp>20240101.120000</timestamp><buildNumber>3</buildNumber>"
                + "</snapshot></versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><snapshot>"
                + "<timestamp>20240315.140530</timestamp><buildNumber>9</buildNumber>"
                + "</snapshot></versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><snapshot>"
                + "<timestamp>20240210.080000</timestamp><buildNumber>5</buildNumber>"
                + "</snapshot></versioning></metadata>"
        ));
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(
            "Newest snapshot timestamp wins",
            out,
            Matchers.containsString("<timestamp>20240315.140530</timestamp>")
        );
        MatcherAssert.assertThat(
            "And its buildNumber is preserved",
            out,
            Matchers.containsString("<buildNumber>9</buildNumber>")
        );
    }

    @Test
    void malformedMemberSkippedRemainingMerged() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><versions><version>1.0</version></versions></versioning>"
                + "</metadata>"
        ));
        // Truncated / malformed body
        merger.mergeMember(stream("<?xml version=\"1.0\"?><metadata><versioning><vers"));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning><versions><version>2.0</version></versions></versioning>"
                + "</metadata>"
        ));
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(out, Matchers.containsString("<version>1.0</version>"));
        MatcherAssert.assertThat(out, Matchers.containsString("<version>2.0</version>"));
        MatcherAssert.assertThat(
            "Two members merged (the malformed one was skipped)",
            merger.membersMerged(),
            Matchers.equalTo(2)
        );
        MatcherAssert.assertThat(
            "The malformed member was counted as skipped",
            merger.membersSkipped(),
            Matchers.equalTo(1)
        );
    }

    @Test
    void allMembersEmptyEmitsMinimalMetadata() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        // No mergeMember calls at all
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(
            "Output is well-formed XML",
            out,
            Matchers.startsWith("<?xml")
        );
        MatcherAssert.assertThat(
            "Output contains a <metadata> root",
            out,
            Matchers.containsString("<metadata")
        );
        MatcherAssert.assertThat(
            "Output contains </metadata> close",
            out,
            Matchers.containsString("</metadata>")
        );
        MatcherAssert.assertThat(
            "No version lines emitted",
            out,
            Matchers.not(Matchers.containsString("<version>"))
        );
    }

    @Test
    void latestAndReleasePickedAsMaxAcrossMembers() {
        final StreamingMetadataMerger merger = new StreamingMetadataMerger();
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning>"
                + "<latest>1.5</latest><release>1.5</release>"
                + "<versions><version>1.5</version></versions>"
                + "</versioning></metadata>"
        ));
        merger.mergeMember(stream(
            "<?xml version=\"1.0\"?><metadata>"
                + "<versioning>"
                + "<latest>2.3</latest><release>2.3</release>"
                + "<versions><version>2.3</version></versions>"
                + "</versioning></metadata>"
        ));
        final String out = merger.toXmlString();
        MatcherAssert.assertThat(out, Matchers.containsString("<latest>2.3</latest>"));
        MatcherAssert.assertThat(out, Matchers.containsString("<release>2.3</release>"));
    }

    private static ByteArrayInputStream stream(final String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
