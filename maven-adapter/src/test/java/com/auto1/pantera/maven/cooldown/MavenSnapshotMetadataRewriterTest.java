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
package com.auto1.pantera.maven.cooldown;

import java.nio.charset.StandardCharsets;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * Phase C rewriter test: filtered DOM round-trips to bytes that still parse.
 *
 * @since 2.2.0
 */
class MavenSnapshotMetadataRewriterTest {

    private static final String XML =
        "<?xml version=\"1.0\"?>\n"
        + "<metadata>\n"
        + "  <versioning>\n"
        + "    <snapshot>\n"
        + "      <timestamp>20260519.090000</timestamp>\n"
        + "      <buildNumber>1</buildNumber>\n"
        + "    </snapshot>\n"
        + "    <snapshotVersions>\n"
        + "      <snapshotVersion>\n"
        + "        <extension>jar</extension>\n"
        + "        <value>1.0-20260519.090000-1</value>\n"
        + "        <updated>20260519090000</updated>\n"
        + "      </snapshotVersion>\n"
        + "    </snapshotVersions>\n"
        + "  </versioning>\n"
        + "</metadata>\n";

    @Test
    void rewriteProducesParseableXml() {
        final MavenSnapshotMetadataParser parser = new MavenSnapshotMetadataParser();
        final MavenSnapshotMetadataRewriter rewriter = new MavenSnapshotMetadataRewriter();
        final Document doc = parser.parse(XML.getBytes(StandardCharsets.UTF_8));
        final byte[] out = rewriter.rewrite(doc);
        final Document round = parser.parse(out);
        MatcherAssert.assertThat(
            round.getElementsByTagName("snapshotVersion").getLength(),
            new IsEqual<>(1)
        );
    }
}
