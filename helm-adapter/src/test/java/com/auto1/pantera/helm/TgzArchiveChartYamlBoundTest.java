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
package com.auto1.pantera.helm;

import com.auto1.pantera.asto.PanteraIOException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Exploit-regression test for the unbounded {@code Chart.yaml}
 * materialisation (resource-dos F45, decompression half).
 *
 * <p>{@code TgzArchive.file("Chart.yaml")} joined every line of the entry
 * into one String with no bound. A highly repetitive {@code Chart.yaml}
 * gzips to almost nothing, so a tiny {@code .tgz} could expand into a
 * heap-sized String on every push. The entry must be capped.</p>
 *
 * @since 2.2.9
 */
final class TgzArchiveChartYamlBoundTest {

    @Test
    void chartYamlAboveTheCapIsRefused() throws IOException {
        // A Chart.yaml twice the cap: tiny once gzipped, huge once inflated.
        final StringBuilder yaml = new StringBuilder("apiVersion: v2\nname: bomb\nversion: 1.0.0\n");
        final String line = "# aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n";
        while (yaml.length() < 2 * TgzArchive.MAX_CHART_YAML_BYTES) {
            yaml.append(line);
        }
        final byte[] tgz = chart(yaml.toString().getBytes(StandardCharsets.UTF_8));
        MatcherAssert.assertThat(
            "the bomb must be small on the wire (this is the whole point)",
            tgz.length < 64 * 1024, new IsEqual<>(true)
        );
        final PanteraIOException failure = Assertions.assertThrows(
            PanteraIOException.class,
            () -> new TgzArchive(tgz).chartYaml()
        );
        MatcherAssert.assertThat(
            "an oversized Chart.yaml must be refused by the cap, never inflated whole",
            failure.getMessage(), new StringContains("limit")
        );
    }

    private static byte[] chart(final byte[] yaml) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (
            GzipCompressorOutputStream gz = new GzipCompressorOutputStream(out);
            TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)
        ) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            final TarArchiveEntry entry = new TarArchiveEntry("bomb/Chart.yaml");
            entry.setSize(yaml.length);
            tar.putArchiveEntry(entry);
            tar.write(yaml);
            tar.closeArchiveEntry();
        }
        return out.toByteArray();
    }
}
